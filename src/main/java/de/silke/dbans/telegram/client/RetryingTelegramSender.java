package de.silke.dbans.telegram.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.silke.dbans.telegram.config.TelegramConfig;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

final class RetryingTelegramSender implements TelegramDeliverySender {

    private static final Logger log = Logger.getLogger("dbans-telegram-addon");
    private static final int MAX_RETRIES = 3;
    private static final Duration MIN_SEND_INTERVAL = Duration.ofSeconds(1);
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(500, 502, 503, 504);

    private final TelegramConfig config;
    private final String apiBaseUrl;
    private final TelegramHttpSender httpSender;
    private final ScheduledExecutorService scheduler;
    private final Set<CompletableFuture<?>> pendingScheduled = ConcurrentHashMap.newKeySet();

    RetryingTelegramSender(@NotNull TelegramConfig config, @NotNull String apiBaseUrl,
                           @NotNull TelegramHttpSender httpSender, @NotNull ScheduledExecutorService scheduler
    ) {
        this.config = config;
        this.apiBaseUrl = apiBaseUrl;
        this.httpSender = httpSender;
        this.scheduler = scheduler;
    }

    @Contract(pure = true)
    static @NotNull TelegramHttpSender defaultHttpSender() {
        return request -> HttpClientHolder.INSTANCE.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient createDefaultHttpClient() {
        return HttpClient.newBuilder()
                         .connectTimeout(Duration.ofSeconds(5))
                         .build();
    }

    private static int parseRetryAfterSeconds(@NotNull String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject params = root.getAsJsonObject("parameters");
            if (params != null) {
                JsonElement retryAfter = params.get("retry_after");
                if (retryAfter != null) {
                    return retryAfter.getAsInt();
                }
            }
        } catch (Exception ignored) {
        }
        return 30;
    }

    @Contract(value = " -> new", pure = true)
    private static @NotNull CancellationException schedulerShutdownException() {
        return new CancellationException("Telegram delivery scheduler is shut down");
    }

    @Override
    public @NotNull CompletableFuture<Void> deliver(@NotNull String chatId, @NotNull String text) {
        return pacedSend(chatId, text);
    }

    @Override
    public void cancelAllPending() {
        List<CompletableFuture<?>> batch;
        while (!(batch = List.copyOf(pendingScheduled)).isEmpty()) {
            for (CompletableFuture<?> pending : batch) {
                pending.completeExceptionally(schedulerShutdownException());
            }
        }
    }

    private @NotNull CompletableFuture<Void> pacedSend(@NotNull String chatId, @NotNull String text) {
        return sendMessage(chatId, text, 0)
                .handle((v, ex) -> ex)
                .thenCompose(ex -> delay().thenCompose(v -> ex == null
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(ex)));
    }

    private @NotNull CompletableFuture<Void> delay() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        trackPending(future);
        scheduleOrFail(future, () -> future.complete(null), MIN_SEND_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        return future;
    }

    private void trackPending(@NotNull CompletableFuture<?> future) {
        pendingScheduled.add(future);
        future.whenComplete((v, ex) -> pendingScheduled.remove(future));
    }

    private void scheduleOrFail(@NotNull CompletableFuture<?> future, @NotNull Runnable task,
                                long delay, @NotNull TimeUnit unit
    ) {
        try {
            scheduler.schedule(task, delay, unit);
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(schedulerShutdownException());
        }
    }

    private @NotNull CompletableFuture<Void> sendMessage(@NotNull String chatId,
                                                         @NotNull String text,
                                                         int attempt
    ) {
        String url = apiBaseUrl + "/bot" + config.getToken() + "/sendMessage";
        String body = "chat_id=" + URLEncoder.encode(chatId, StandardCharsets.UTF_8)
                      + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                                         .uri(URI.create(url))
                                         .header("Content-Type", "application/x-www-form-urlencoded")
                                         .timeout(Duration.ofSeconds(10))
                                         .POST(HttpRequest.BodyPublishers.ofString(
                                                 body,
                                                 StandardCharsets.UTF_8
                                         ))
                                         .build();

        return httpSender.send(request)
                         .handle((response, ex) -> ex != null
                                 ? retryOnNetworkError(chatId, text, attempt, ex)
                                 : handleResponse(chatId, text, response, attempt))
                         .thenCompose(future -> future);
    }

    private @NotNull CompletableFuture<Void> retryOnNetworkError(
            @NotNull String chatId, @NotNull String text, int attempt, @NotNull Throwable ex
    ) {
        if (attempt >= MAX_RETRIES) {
            log.log(Level.SEVERE, "Failed to send Telegram message to " + chatId + " after " + MAX_RETRIES + " retries", ex);
            return CompletableFuture.failedFuture(ex);
        }
        log.log(Level.WARNING, "Failed to send Telegram message to " + chatId
                               + ". Retrying (attempt " + (attempt + 1) + "/" + MAX_RETRIES + ")", ex);
        return scheduleRetry(chatId, text, attempt, MIN_SEND_INTERVAL.toSeconds());
    }

    @SuppressWarnings("MethodWithMultipleReturnPoints")
    private @NotNull CompletableFuture<Void> handleResponse(
            @NotNull String chatId,
            @NotNull String text,
            @NotNull HttpResponse<String> response,
            int attempt
    ) {
        int status = response.statusCode();
        if (status == 200) {
            return CompletableFuture.completedFuture(null);
        }

        if (status == 429) {
            if (attempt >= MAX_RETRIES) {
                log.warning("Telegram rate limit hit. Message to " + chatId + " dropped after " + MAX_RETRIES + " retries");
                return CompletableFuture.failedFuture(new TelegramApiException(status));
            }

            int retryAfter = parseRetryAfterSeconds(response.body());
            log.warning("Telegram rate limit for " + chatId + " (retry_after=" + retryAfter + "s). Retrying in "
                        + retryAfter + "s (attempt " + (attempt + 1) + "/" + MAX_RETRIES + ")");
            return scheduleRetry(chatId, text, attempt, retryAfter);
        }

        if (RETRYABLE_STATUS_CODES.contains(status)) {
            if (attempt >= MAX_RETRIES) {
                log.warning("Telegram server error " + status + ". Message to " + chatId + " dropped after " + MAX_RETRIES + " retries");
                return CompletableFuture.failedFuture(new TelegramApiException(status));
            }
            log.warning("Telegram server error " + status + " for " + chatId + ". Retrying "
                        + "(attempt " + (attempt + 1) + "/" + MAX_RETRIES + ")");
            return scheduleRetry(chatId, text, attempt, MIN_SEND_INTERVAL.toSeconds());
        }
        log.warning("Telegram API returned unexpected status " + status + " for " + chatId + ": " + response.body());
        return CompletableFuture.failedFuture(new TelegramApiException(status));
    }

    private @NotNull CompletableFuture<Void> scheduleRetry(
            @NotNull String chatId, @NotNull String text, int attempt, long delaySeconds
    ) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        trackPending(future);
        scheduleOrFail(future, () -> sendMessage(chatId, text, attempt + 1)
                               .whenComplete((v, ex) -> {
                                   if (ex != null) {
                                       future.completeExceptionally(ex);
                                   } else {
                                       future.complete(null);
                                   }
                               }),
                       delaySeconds, TimeUnit.SECONDS
        );
        return future;
    }

    @UtilityClass
    private static final class HttpClientHolder {

        private static final HttpClient INSTANCE = createDefaultHttpClient();
    }

}