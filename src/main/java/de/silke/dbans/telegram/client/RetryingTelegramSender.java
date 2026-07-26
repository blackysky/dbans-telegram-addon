package de.silke.dbans.telegram.client;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

final class RetryingTelegramSender implements CancellableTelegramDeliverySender {

    private static final Logger log = Logger.getLogger("dbans-telegram-addon");
    private static final int MAX_RETRIES = 3;
    private static final Duration MIN_SEND_INTERVAL = Duration.ofSeconds(1);

    private final TelegramApiTransport transport;
    private final ScheduledExecutorService scheduler;
    private final Object lock = new Object();
    private final Map<CompletableFuture<?>, ScheduledFuture<?>> pendingScheduled = new HashMap<>();
    private boolean cancelled = false;

    @TestOnly
    private volatile Runnable beforeTransportAttemptHookForTesting = () -> {
    };

    RetryingTelegramSender(@NotNull TelegramApiTransport transport,
                           @NotNull ScheduledExecutorService scheduler
    ) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Contract(value = " -> new", pure = true)
    private static @NotNull CancellationException cancellationException() {
        return new CancellationException("Telegram delivery sender is permanently cancelled");
    }

    @Override
    public @NotNull CompletableFuture<Void> deliver(@NotNull String chatId, @NotNull String text) {
        synchronized (lock) {
            if (cancelled) {
                return CompletableFuture.failedFuture(cancellationException());
            }
        }
        return pacedSend(chatId, text);
    }

    @Override
    public void cancelAllPending() {
        List<Map.Entry<CompletableFuture<?>, ScheduledFuture<?>>> toCancel;
        synchronized (lock) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            toCancel = List.copyOf(pendingScheduled.entrySet());
            pendingScheduled.clear();
        }
        for (Map.Entry<CompletableFuture<?>, ScheduledFuture<?>> entry : toCancel) {
            entry.getValue().cancel(false);
            entry.getKey().completeExceptionally(cancellationException());
        }
    }

    private @NotNull CompletableFuture<Void> pacedSend(@NotNull String chatId, @NotNull String text) {
        return attemptTransportSend(chatId, text, 0)
                .handle((v, ex) -> ex)
                .thenCompose(ex -> delay().thenCompose(v -> ex == null
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(ex)));
    }

    private @NotNull CompletableFuture<Void> delay() {
        return scheduleDelayed(
                MIN_SEND_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS,
                future -> future.complete(null)
        );
    }

    private @NotNull CompletableFuture<Void> scheduleDelayed(
            long delay, @NotNull TimeUnit unit, @NotNull Consumer<CompletableFuture<Void>> onFire
    ) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        synchronized (lock) {
            if (cancelled) {
                return CompletableFuture.failedFuture(cancellationException());
            }
            ScheduledFuture<?> scheduledFuture;
            try {
                scheduledFuture = scheduler.schedule(() -> runIfNotCancelled(future, onFire), delay, unit);
            } catch (RejectedExecutionException e) {
                return CompletableFuture.failedFuture(cancellationException());
            }
            pendingScheduled.put(future, scheduledFuture);
        }
        future.whenComplete((v, ex) -> removeFromPending(future));
        return future;
    }

    private void runIfNotCancelled(@NotNull CompletableFuture<Void> future,
                                   @NotNull Consumer<CompletableFuture<Void>> onFire
    ) {
        synchronized (lock) {
            if (cancelled) {
                return;
            }
        }
        onFire.accept(future);
    }

    private void removeFromPending(@NotNull CompletableFuture<?> future) {
        synchronized (lock) {
            pendingScheduled.remove(future);
        }
    }

    @Contract(pure = true)
    @TestOnly
    int pendingScheduledCountForTesting() {
        synchronized (lock) {
            return pendingScheduled.size();
        }
    }

    @Contract(mutates = "this")
    @TestOnly
    void setBeforeTransportAttemptHookForTesting(@NotNull Runnable hook) {
        this.beforeTransportAttemptHookForTesting = Objects.requireNonNull(hook, "hook");
    }

    private @NotNull CompletableFuture<Void> attemptTransportSend(@NotNull String chatId, @NotNull String text,
                                                                  int attempt
    ) {
        synchronized (lock) {
            if (cancelled) {
                return CompletableFuture.failedFuture(cancellationException());
            }
        }
        beforeTransportAttemptHookForTesting.run();
        synchronized (lock) {
            if (cancelled) {
                return CompletableFuture.failedFuture(cancellationException());
            }
        }
        CompletableFuture<HttpResponse<String>> responseFuture = transport.send(chatId, text);
        return responseFuture.handle((response, ex) -> ex != null
                                     ? handleFailure(chatId, text, attempt, ex)
                                     : handleResponse(chatId, text, response, attempt))
                             .thenCompose(future -> future);
    }

    private @NotNull CompletableFuture<Void> handleFailure(
            @NotNull String chatId, @NotNull String text, int attempt, @NotNull Throwable ex
    ) {
        if (ex instanceof TelegramRequestPreparationException || ex instanceof CancellationException) {
            return CompletableFuture.failedFuture(ex);
        }
        return retryOnNetworkError(chatId, text, attempt, ex);
    }

    private @NotNull CompletableFuture<Void> retryOnNetworkError(
            @NotNull String chatId, @NotNull String text, int attempt, @NotNull Throwable ex
    ) {
        if (attempt >= MAX_RETRIES) {
            return CompletableFuture.failedFuture(ex);
        }
        log.log(Level.WARNING, "Failed to send Telegram message to " + chatId
                               + ". Retrying (attempt " + (attempt + 1) + "/" + MAX_RETRIES + ")", ex);
        return scheduleRetry(chatId, text, attempt, MIN_SEND_INTERVAL.toSeconds());
    }

    private @NotNull CompletableFuture<Void> handleResponse(
            @NotNull String chatId, @NotNull String text, @NotNull HttpResponse<String> response, int attempt
    ) {
        TelegramHttpResponseClassifier.Result result = TelegramHttpResponseClassifier.classify(response.statusCode(), response.body());
        return switch (result.classification()) {
            case SUCCESS -> CompletableFuture.completedFuture(null);
            case RATE_LIMITED -> handleRateLimited(chatId, text, attempt, result);
            case TEMPORARY_FAILURE -> handleTemporaryFailure(chatId, text, attempt, result);
            case PERMANENT_FAILURE -> CompletableFuture.failedFuture(new TelegramApiException(result.statusCode()));
        };
    }

    private @NotNull CompletableFuture<Void> handleRateLimited(
            @NotNull String chatId, @NotNull String text, int attempt,
            @NotNull TelegramHttpResponseClassifier.Result result
    ) {
        if (attempt >= MAX_RETRIES) {
            return CompletableFuture.failedFuture(new TelegramApiException(result.statusCode()));
        }
        int retryAfter = result.retryAfterSeconds();
        log.warning("Telegram rate limit for " + chatId + " (retry_after=" + retryAfter + "s). Retrying in "
                    + retryAfter + "s (attempt " + (attempt + 1) + "/" + MAX_RETRIES + ")");
        return scheduleRetry(chatId, text, attempt, retryAfter);
    }

    private @NotNull CompletableFuture<Void> handleTemporaryFailure(
            @NotNull String chatId, @NotNull String text, int attempt,
            @NotNull TelegramHttpResponseClassifier.Result result
    ) {
        if (attempt >= MAX_RETRIES) {
            return CompletableFuture.failedFuture(new TelegramApiException(result.statusCode()));
        }
        log.warning("Telegram server error " + result.statusCode() + " for " + chatId + ". Retrying "
                    + "(attempt " + (attempt + 1) + "/" + MAX_RETRIES + ")");
        return scheduleRetry(chatId, text, attempt, MIN_SEND_INTERVAL.toSeconds());
    }

    private @NotNull CompletableFuture<Void> scheduleRetry(
            @NotNull String chatId, @NotNull String text, int attempt, long delaySeconds
    ) {
        return scheduleDelayed(delaySeconds, TimeUnit.SECONDS, future ->
                attemptTransportSend(chatId, text, attempt + 1)
                        .whenComplete((v, ex) -> {
                            if (ex != null) {
                                future.completeExceptionally(ex);
                            } else {
                                future.complete(null);
                            }
                        }));
    }
}