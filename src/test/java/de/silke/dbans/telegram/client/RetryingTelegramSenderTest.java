package de.silke.dbans.telegram.client;

import de.silke.dbans.telegram.config.TelegramConfig;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryingTelegramSenderTest {

    private static final String CHAT_ID = "chat-1";

    private static @NotNull HttpResponse<String> fakeResponse(int status, @NotNull String body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpRequest request() {
                return null;
            }

            @Override
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (a, b) -> true);
            }

            @Override
            public String body() {
                return body;
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("http://localhost/");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    private static @NotNull TelegramConfig config() {
        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of(CHAT_ID));
        return new TelegramConfig(yaml);
    }

    @Test
    @Timeout(5)
    void deliver_afterCancellation_performsNoHttpCallAndFailsImmediately() {
        AtomicInteger httpCalls = new AtomicInteger();
        ManualScheduler scheduler = new ManualScheduler();
        RetryingTelegramSender sender = new RetryingTelegramSender(
                new TelegramApiTransport(config(), "http://unused", request -> {
                    httpCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(fakeResponse(200, "{\"ok\":true}"));
                }),
                scheduler
        );

        sender.cancelAllPending();
        CompletableFuture<Void> result = sender.deliver(CHAT_ID, "hello");

        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
        assertThat(httpCalls.get()).isZero();
    }

    @Test
    @Timeout(5)
    void cancelAllPending_isIdempotent() {
        ManualScheduler scheduler = new ManualScheduler();
        RetryingTelegramSender sender = new RetryingTelegramSender(
                new TelegramApiTransport(config(), "http://unused",
                                         request -> CompletableFuture.completedFuture(fakeResponse(200, "{\"ok\":true}"))),
                scheduler
        );

        sender.cancelAllPending();
        sender.cancelAllPending();
        sender.cancelAllPending();
    }

    @Test
    @Timeout(5)
    void retryScheduledJustBeforeCancellation_isCancelledAndNeverFiresAsHttpCall() {
        AtomicInteger httpCalls = new AtomicInteger();
        ManualScheduler scheduler = new ManualScheduler();
        RetryingTelegramSender sender = new RetryingTelegramSender(
                new TelegramApiTransport(config(), "http://unused", request -> {
                    httpCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(fakeResponse(503, "{\"ok\":false}"));
                }),
                scheduler
        );

        CompletableFuture<Void> result = sender.deliver(CHAT_ID, "hello");
        assertThat(httpCalls.get()).isEqualTo(1);
        assertThat(scheduler.pendingTaskCount()).isGreaterThanOrEqualTo(1);

        sender.cancelAllPending();
        scheduler.fireAllPendingIgnoringResult();

        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS)).cause().isInstanceOf(CancellationException.class);
        assertThat(httpCalls.get()).isEqualTo(1);
    }

    @Test
    @Timeout(5)
    void schedulerRejection_completesTheReturnedFutureRatherThanHanging() {
        ManualScheduler scheduler = new ManualScheduler();
        scheduler.startRejecting();
        RetryingTelegramSender sender = new RetryingTelegramSender(
                new TelegramApiTransport(config(), "http://unused",
                                         request -> CompletableFuture.completedFuture(fakeResponse(503, "{\"ok\":false}"))),
                scheduler
        );

        CompletableFuture<Void> result = sender.deliver(CHAT_ID, "hello");

        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS)).cause().isInstanceOf(CancellationException.class);
    }

    @Test
    @Timeout(5)
    void trackedScheduledFuture_isRemovedFromPendingSetOnceItCompletesNormally() {
        ManualScheduler scheduler = new ManualScheduler();
        RetryingTelegramSender sender = new RetryingTelegramSender(
                new TelegramApiTransport(config(), "http://unused",
                                         request -> CompletableFuture.completedFuture(fakeResponse(200, "{\"ok\":true}"))),
                scheduler
        );

        CompletableFuture<Void> result = sender.deliver(CHAT_ID, "hello");
        assertThat(result).isNotDone();
        assertThat(scheduler.pendingTaskCount()).isEqualTo(1);
        assertThat(sender.pendingScheduledCountForTesting()).isEqualTo(1);

        scheduler.fireAll();

        assertThat(result).isCompletedWithValue(null);
        assertThat(sender.pendingScheduledCountForTesting()).isZero();
        sender.cancelAllPending();
    }

    @Test
    @Timeout(5)
    void deliver_synchronousTransportFailure_neverThrowsAndEventuallyFailsAfterRetriesExhaust() {
        ManualScheduler scheduler = new ManualScheduler();
        RetryingTelegramSender sender = new RetryingTelegramSender(
                new TelegramApiTransport(config(), "http://unused", request -> {
                    throw new IllegalArgumentException("malformed request");
                }),
                scheduler
        );

        CompletableFuture<Void> result = sender.deliver(CHAT_ID, "hello");
        assertThat(result).isNotDone();

        for (int i = 0; i < 6; i++) {
            scheduler.fireAll();
        }

        assertThat(result).isCompletedExceptionally();
        assertThatThrownBy(result::get).cause().isInstanceOf(IllegalArgumentException.class).hasMessage("malformed request");
    }

    private static final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {

        private final List<ManualScheduledFuture> tasks = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean rejecting = false;

        void startRejecting() {
            rejecting = true;
        }

        int pendingTaskCount() {
            synchronized (tasks) {
                return (int) tasks.stream().filter(t -> !t.isDone()).count();
            }
        }

        void fireAll() {
            List<ManualScheduledFuture> snapshot = List.copyOf(tasks);
            for (ManualScheduledFuture task : snapshot) {
                task.runIfNotCancelled();
            }
        }

        void fireAllPendingIgnoringResult() {
            fireAll();
        }

        @Override
        public ScheduledFuture<?> schedule(@NotNull Runnable command, long delay, @NotNull TimeUnit unit) {
            if (rejecting) {
                throw new RejectedExecutionException("manual scheduler is rejecting");
            }
            ManualScheduledFuture task = new ManualScheduledFuture(command);
            tasks.add(task);
            return task;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(@NotNull Callable<V> callable, long delay, @NotNull TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(@NotNull Runnable command, long initialDelay, long period,
                                                      @NotNull TimeUnit unit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(@NotNull Runnable command, long initialDelay, long delay,
                                                         @NotNull TimeUnit unit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public @NotNull List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(@NotNull Runnable command) {
            command.run();
        }
    }

    private static final class ManualScheduledFuture implements ScheduledFuture<Object> {

        private final Runnable command;
        private volatile boolean cancelled = false;
        private volatile boolean ran = false;

        ManualScheduledFuture(@NotNull Runnable command) {
            this.command = command;
        }

        void runIfNotCancelled() {
            if (!cancelled && !ran) {
                ran = true;
                command.run();
            }
        }

        @Override
        public boolean isDone() {
            return cancelled || ran;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean wasPending = !cancelled && !ran;
            cancelled = true;
            return wasPending;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, @NotNull TimeUnit unit) {
            return null;
        }

        @Override
        public long getDelay(@NotNull TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(@NotNull Delayed o) {
            return 0;
        }
    }
}