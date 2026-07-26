package de.silke.dbans.telegram.client;

import com.sun.net.httpserver.HttpServer;
import de.silke.dbans.telegram.config.TelegramConfig;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramClientTest {

    private HttpServer server;
    private TelegramClient client;
    private AtomicInteger requestCount;

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

    private static void awaitQuietly(@NotNull CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static @NotNull CancellableTelegramDeliverySender getFake(CountDownLatch cancelEntered,
                                                                      CountDownLatch releaseCancel
    ) {
        CompletableFuture<Void> stuck1 = new CompletableFuture<>();
        CompletableFuture<Void> stuck2 = new CompletableFuture<>();
        return new CancellableTelegramDeliverySender() {
            @Override
            public @NotNull CompletableFuture<Void> deliver(@NotNull String chatId, @NotNull String text) {
                return "1".equals(chatId) ? stuck1 : stuck2;
            }

            @Override
            public void cancelAllPending() {
                cancelEntered.countDown();
                awaitQuietly(releaseCancel);
            }
        };
    }

    private void startServer(IntFunction<StubResponse> responder) throws Exception {
        requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            StubResponse response = responder.apply(requestCount.getAndIncrement());
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("123"));
        TelegramConfig config = new TelegramConfig(yaml);

        client = new TelegramClient(config, "http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.shutdown();
        if (server != null) server.stop(0);
    }

    @Test
    @Timeout(10)
    void retriesAfter429ThenSucceeds() throws Exception {
        startServer(attempt -> attempt == 0
                ? new StubResponse(429, "{\"parameters\":{\"retry_after\":1}}")
                : new StubResponse(200, "{\"ok\":true}"));

        client.sendMessage("hello").get(9, TimeUnit.SECONDS);

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    @Timeout(10)
    void givesUpAfterMaxRetries() throws Exception {
        startServer(attempt -> new StubResponse(429, "{\"parameters\":{\"retry_after\":1}}"));

        CompletableFuture<Void> future = client.sendMessage("hello");

        assertThatThrownBy(() -> future.get(9, TimeUnit.SECONDS))
                .cause().isInstanceOf(TelegramApiException.class);
        assertThat(requestCount.get()).isEqualTo(4);
    }

    @Test
    @Timeout(5)
    void succeedsOnFirstAttempt() throws Exception {
        startServer(attempt -> new StubResponse(200, "{\"ok\":true}"));

        client.sendMessage("hello").get(4, TimeUnit.SECONDS);

        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    @Timeout(5)
    void doesNotRetryOnNonRateLimitError() throws Exception {
        startServer(attempt -> new StubResponse(400, "{\"ok\":false,\"description\":\"Bad Request\"}"));

        CompletableFuture<Void> future = client.sendMessage("hello");

        assertThatThrownBy(() -> future.get(4, TimeUnit.SECONDS))
                .cause().isInstanceOf(TelegramApiException.class);
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    @Timeout(10)
    void retriesOnServerErrorThenSucceeds() throws Exception {
        startServer(attempt -> attempt == 0
                ? new StubResponse(503, "{\"ok\":false,\"description\":\"Service Unavailable\"}")
                : new StubResponse(200, "{\"ok\":true}"));

        client.sendMessage("hello").get(9, TimeUnit.SECONDS);

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    @Timeout(10)
    void givesUpAfterMaxRetriesOnServerError() throws Exception {
        startServer(attempt -> new StubResponse(500, "{\"ok\":false,\"description\":\"Internal Server Error\"}"));

        CompletableFuture<Void> future = client.sendMessage("hello");

        assertThatThrownBy(() -> future.get(9, TimeUnit.SECONDS))
                .cause().isInstanceOf(TelegramApiException.class);
        assertThat(requestCount.get()).isEqualTo(4);
    }

    @Test
    @Timeout(5)
    void queueStaysUsableAfterAFailedSend() throws Exception {
        startServer(attempt -> attempt == 0
                ? new StubResponse(400, "{\"ok\":false,\"description\":\"Bad Request\"}")
                : new StubResponse(200, "{\"ok\":true}"));

        CompletableFuture<Void> failed = client.sendMessage("hello");
        assertThatThrownBy(() -> failed.get(4, TimeUnit.SECONDS))
                .cause().isInstanceOf(TelegramApiException.class);

        client.sendMessage("hello again").get(4, TimeUnit.SECONDS);

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    @Timeout(10)
    void retriesAfterNetworkFailureThenSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        client = clientWithSender(request -> attempts.getAndIncrement() == 0
                ? CompletableFuture.failedFuture(new IOException("simulated network failure"))
                : CompletableFuture.completedFuture(fakeResponse(200, "{\"ok\":true}")));

        client.sendMessage("hello").get(9, TimeUnit.SECONDS);

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @Timeout(10)
    void failsAfterNetworkRetryLimit() {
        AtomicInteger attempts = new AtomicInteger();
        IOException networkError = new IOException("simulated network failure");
        client = clientWithSender(request -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(networkError);
        });

        CompletableFuture<Void> future = client.sendMessage("hello");

        assertThatThrownBy(() -> future.get(9, TimeUnit.SECONDS))
                .cause().isSameAs(networkError);
        assertThat(attempts.get()).isEqualTo(4);
    }

    @Test
    @Timeout(5)
    void preservesMessageOrderAfterFailure() throws Exception {
        List<String> receivedBodies = Collections.synchronizedList(new ArrayList<>());
        List<Long> receivedAt = Collections.synchronizedList(new ArrayList<>());

        requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            int attempt = requestCount.getAndIncrement();
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedAt.add(System.nanoTime());

            StubResponse response = attempt == 0
                    ? new StubResponse(400, "{\"ok\":false,\"description\":\"Bad Request\"}")
                    : new StubResponse(200, "{\"ok\":true}");
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("123"));
        TelegramConfig config = new TelegramConfig(yaml);
        client = new TelegramClient(config, "http://localhost:" + server.getAddress().getPort());

        CompletableFuture<Void> first = client.sendMessage("hello");
        CompletableFuture<Void> second = client.sendMessage("hello again");

        assertThatThrownBy(() -> first.get(4, TimeUnit.SECONDS))
                .cause().isInstanceOf(TelegramApiException.class);
        second.get(4, TimeUnit.SECONDS);

        assertThat(receivedBodies).hasSize(2);
        assertThat(receivedBodies.get(0)).endsWith("text=hello");
        assertThat(receivedBodies.get(1)).endsWith("text=hello+again");
        assertThat(receivedAt.get(1) - receivedAt.get(0)).isGreaterThanOrEqualTo(Duration.ofMillis(800).toNanos());
    }

    @Test
    @Timeout(5)
    void shutdown_rejectsNewMessages_immediatelyWithoutTouchingNetwork() throws Exception {
        AtomicInteger sendCount = new AtomicInteger();
        CompletableFuture<HttpResponse<String>> pendingResponse = new CompletableFuture<>();
        client = clientWithSender(request -> {
            sendCount.incrementAndGet();
            return pendingResponse;
        });

        CompletableFuture<Void> inFlight = client.sendMessage("first");
        client.shutdown();

        CompletableFuture<Void> rejected = client.sendMessage("second");
        assertThatThrownBy(() -> rejected.get(1, TimeUnit.SECONDS))
                .cause().isInstanceOf(TelegramClientShuttingDownException.class);
        assertThat(sendCount.get()).isEqualTo(1);

        pendingResponse.complete(fakeResponse(200, "{\"ok\":true}"));
        inFlight.get(4, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(5)
    void shutdown_isIdempotent_repeatedCallsReturnSameFuture() throws Exception {
        startServer(attempt -> new StubResponse(200, "{\"ok\":true}"));

        client.sendMessage("hello").get(4, TimeUnit.SECONDS);

        CompletableFuture<Void> first = client.shutdown();
        CompletableFuture<Void> second = client.shutdown();
        CompletableFuture<Void> third = client.shutdown();

        assertThat(second).isSameAs(first);
        assertThat(third).isSameAs(first);
        first.get(4, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(5)
    void shutdown_drainsAcceptedWorkBeforeCompletingNormally() throws Exception {
        startServer(attempt -> new StubResponse(200, "{\"ok\":true}"));

        client.sendMessage("hello");
        CompletableFuture<Void> shutdown = client.shutdown();

        shutdown.get(4, TimeUnit.SECONDS);
        assertThat(client.aggregateQueueStatistics().depth()).isZero();
    }

    @Test
    @Timeout(5)
    void shutdown_duringPacingDelay_afterTimeoutExpires_completesReturnedFutureExceptionally() throws Exception {
        client = clientWithSenderAndShutdownTimeout(
                request -> CompletableFuture.completedFuture(fakeResponse(200, "{\"ok\":true}")),
                Duration.ZERO
        );

        CompletableFuture<Void> future = client.sendMessage("hello");
        client.shutdown();

        assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                .cause().isInstanceOf(CancellationException.class);
    }

    @Test
    @Timeout(5)
    void shutdown_duringRetryDelay_afterTimeoutExpires_completesReturnedFutureExceptionally() throws Exception {
        client = clientWithSenderAndShutdownTimeout(
                request -> CompletableFuture.completedFuture(fakeResponse(503, "{\"ok\":false}")),
                Duration.ZERO
        );

        CompletableFuture<Void> future = client.sendMessage("hello");
        client.shutdown();

        assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                .cause().isInstanceOf(CancellationException.class);
    }

    @Test
    @Timeout(5)
    void shutdown_forcedTimeout_bringsQueueDepthBackToZero() throws Exception {
        CompletableFuture<HttpResponse<String>> neverCompletes = new CompletableFuture<>();
        client = clientWithSenderAndShutdownTimeout(request -> neverCompletes, Duration.ZERO);

        client.sendMessage("hello");
        client.shutdown().get(3, TimeUnit.SECONDS);

        assertThat(client.aggregateQueueStatistics().depth()).isZero();
    }

    @Test
    @Timeout(5)
    void shutdown_terminatesTheSchedulerItOwnsAfterClientTerminationCompletes() throws Exception {
        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("123"));
        TelegramConfig config = new TelegramConfig(yaml);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TelegramApiTransport transport = new TelegramApiTransport(config, "http://unused",
                                                                  request -> CompletableFuture.completedFuture(fakeResponse(200, "{\"ok\":true}")));
        client = new TelegramClient(config, scheduler, new RetryingTelegramSender(transport, scheduler));

        client.shutdown().get(4, TimeUnit.SECONDS);

        assertThat(scheduler.awaitTermination(4, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @Timeout(10)
    void sendMessageRacingWithShutdown_neverLeavesAcceptedFutureHanging() throws Exception {
        for (int i = 0; i < 20; i++) {
            client = clientWithSenderAndShutdownTimeout(
                    request -> CompletableFuture.completedFuture(fakeResponse(200, "{\"ok\":true}")), Duration.ZERO);

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            @SuppressWarnings("unchecked")
            CompletableFuture<Void>[] result = new CompletableFuture[1];

            Thread sender = new Thread(() -> {
                ready.countDown();
                awaitQuietly(go);
                result[0] = client.sendMessage("hello");
            });
            Thread shutdowner = new Thread(() -> {
                ready.countDown();
                awaitQuietly(go);
                client.shutdown();
            });
            sender.start();
            shutdowner.start();
            ready.await();
            go.countDown();
            sender.join();
            shutdowner.join();

            assertThat(result[0]).isNotNull();
            result[0].exceptionally(ex -> null).get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(5)
    void fullQueueForOneChat_failsAggregatedFutureAndIsObservableInStatistics() throws Exception {
        CompletableFuture<Void> stuckChat1 = new CompletableFuture<>();
        CancellableTelegramDeliverySender fake = new CancellableTelegramDeliverySender() {
            @Override
            public @NotNull CompletableFuture<Void> deliver(@NotNull String chatId, @NotNull String text) {
                return "1".equals(chatId) ? stuckChat1 : CompletableFuture.completedFuture(null);
            }

            @Override
            public void cancelAllPending() {
            }
        };

        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("1", "2"));
        yaml.set("queue.capacity", 1);
        yaml.set("queue.shutdown-timeout-seconds", 0);
        TelegramConfig config = new TelegramConfig(yaml);
        client = new TelegramClient(config, Executors.newSingleThreadScheduledExecutor(), fake);

        client.sendMessage("first");
        CompletableFuture<Void> second = client.sendMessage("second");

        assertThatThrownBy(() -> second.get(2, TimeUnit.SECONDS))
                .cause().isInstanceOf(TelegramQueueFullException.class);

        assertThat(client.queueStatistics().get("1").depth()).isEqualTo(1);
        assertThat(client.queueStatistics().get("1").dropped()).isEqualTo(1);
        assertThat(client.queueStatistics().get("2").depth()).isZero();
        assertThat(client.queueStatistics().get("2").dropped()).isZero();
        assertThat(client.aggregateQueueStatistics().dropped()).isEqualTo(1);
    }

    @Test
    @Timeout(5)
    void oneSlowChat_doesNotBlockAnotherChatsProgress() throws Exception {
        CompletableFuture<Void> stuckChat1 = new CompletableFuture<>();
        CancellableTelegramDeliverySender fake = new CancellableTelegramDeliverySender() {
            @Override
            public @NotNull CompletableFuture<Void> deliver(@NotNull String chatId, @NotNull String text) {
                return "1".equals(chatId) ? stuckChat1 : CompletableFuture.completedFuture(null);
            }

            @Override
            public void cancelAllPending() {
            }
        };

        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("1", "2"));
        yaml.set("queue.shutdown-timeout-seconds", 0);
        TelegramConfig config = new TelegramConfig(yaml);
        client = new TelegramClient(config, Executors.newSingleThreadScheduledExecutor(), fake);

        client.sendMessage("a");
        client.sendMessage("b");
        client.sendMessage("c");

        assertThat(client.queueStatistics().get("2").depth()).isZero();
        assertThat(client.queueStatistics().get("1").depth()).isEqualTo(3);
    }

    @Test
    @Timeout(10)
    void sendMessage_pausedMidBroadcastByTestHook_stillReachesEveryChatBeforeConcurrentShutdownCanTakeEffect()
            throws Exception {
        AtomicInteger deliverCalls = new AtomicInteger();
        CancellableTelegramDeliverySender fake = new CancellableTelegramDeliverySender() {
            @Override
            public @NotNull CompletableFuture<Void> deliver(@NotNull String chatId, @NotNull String text) {
                deliverCalls.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void cancelAllPending() {
            }
        };

        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("1", "2"));
        yaml.set("queue.shutdown-timeout-seconds", 0);
        TelegramConfig config = new TelegramConfig(yaml);
        client = new TelegramClient(config, Executors.newSingleThreadScheduledExecutor(), fake);

        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        client.setAfterAcceptanceCheckHookForTesting(() -> {
            paused.countDown();
            awaitQuietly(resume);
        });

        AtomicReference<CompletableFuture<Void>> broadcastResult = new AtomicReference<>();
        Thread sender = new Thread(() -> broadcastResult.set(client.sendMessage("hello")));
        sender.start();

        assertThat(paused.await(5, TimeUnit.SECONDS)).isTrue();
        Thread shutdowner = new Thread(client::shutdown);
        shutdowner.start();

        while (!client.hasThreadsQueuedForLifecycleLockForTesting()) {
            Thread.onSpinWait();
        }
        resume.countDown();

        sender.join(5000);
        shutdowner.join(5000);

        assertThat(broadcastResult.get()).isNotNull();
        broadcastResult.get().get(3, TimeUnit.SECONDS);
        assertThat(deliverCalls.get()).isEqualTo(2);
    }

    @Test
    @Timeout(5)
    void sendMessage_blockedInsideDeliverCollaborator_stillLetsShutdownProceedConcurrently() throws Exception {
        CountDownLatch deliverEntered = new CountDownLatch(1);
        CountDownLatch releaseDeliver = new CountDownLatch(1);
        CancellableTelegramDeliverySender fake = new CancellableTelegramDeliverySender() {
            @Override
            public @NotNull CompletableFuture<Void> deliver(@NotNull String chatId, @NotNull String text) {
                deliverEntered.countDown();
                awaitQuietly(releaseDeliver);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void cancelAllPending() {
            }
        };

        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("123"));
        yaml.set("queue.shutdown-timeout-seconds", 0);
        TelegramConfig config = new TelegramConfig(yaml);
        client = new TelegramClient(config, Executors.newSingleThreadScheduledExecutor(), fake);

        Thread sender = new Thread(() -> client.sendMessage("hello"));
        sender.start();
        assertThat(deliverEntered.await(4, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> shutdownFuture = client.shutdown();
        CompletableFuture<Void> rejected = client.sendMessage("too late");
        assertThatThrownBy(() -> rejected.get(1, TimeUnit.SECONDS))
                .cause().isInstanceOf(TelegramClientShuttingDownException.class);

        releaseDeliver.countDown();
        sender.join(4000);
        shutdownFuture.get(4, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(5)
    void deliver_callingClientShutdownSynchronously_doesNotDeadlock() throws Exception {
        AtomicReference<CompletableFuture<Void>> shutdownResult = new AtomicReference<>();
        CancellableTelegramDeliverySender fake = new CancellableTelegramDeliverySender() {
            @Override
            public @NotNull CompletableFuture<Void> deliver(@NotNull String chatId, @NotNull String text) {
                shutdownResult.set(client.shutdown());
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void cancelAllPending() {
            }
        };

        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("123"));
        yaml.set("queue.shutdown-timeout-seconds", 0);
        TelegramConfig config = new TelegramConfig(yaml);
        client = new TelegramClient(config, Executors.newSingleThreadScheduledExecutor(), fake);

        client.sendMessage("hello").get(4, TimeUnit.SECONDS);

        assertThat(shutdownResult.get()).isNotNull();
        shutdownResult.get().get(4, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(5)
    void shutdown_secondCaller_cannotReturnBeforeAcceptanceTransitionCompletes_andBothShareOneFuture()
            throws Exception {
        startServer(attempt -> new StubResponse(200, "{\"ok\":true}"));

        CountDownLatch pausedInTransition = new CountDownLatch(1);
        CountDownLatch releaseTransition = new CountDownLatch(1);
        client.setBeforeShutdownTransitionHookForTesting(() -> {
            pausedInTransition.countDown();
            awaitQuietly(releaseTransition);
        });

        AtomicReference<CompletableFuture<Void>> firstResult = new AtomicReference<>();
        Thread firstCaller = new Thread(() -> firstResult.set(client.shutdown()));
        firstCaller.start();
        assertThat(pausedInTransition.await(4, TimeUnit.SECONDS)).isTrue();

        CountDownLatch secondReturned = new CountDownLatch(1);
        AtomicReference<CompletableFuture<Void>> secondResult = new AtomicReference<>();
        Thread secondCaller = new Thread(() -> {
            secondResult.set(client.shutdown());
            secondReturned.countDown();
        });
        secondCaller.start();

        while (!client.hasThreadsQueuedForLifecycleLockForTesting()) {
            Thread.onSpinWait();
        }
        assertThat(secondReturned.getCount()).isEqualTo(1);

        releaseTransition.countDown();
        firstCaller.join(4000);
        secondCaller.join(4000);

        assertThat(secondResult.get()).isSameAs(firstResult.get());

        CompletableFuture<Void> rejected = client.sendMessage("too late");
        assertThatThrownBy(() -> rejected.get(1, TimeUnit.SECONDS))
                .cause().isInstanceOf(TelegramClientShuttingDownException.class);

        firstResult.get().get(4, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(5)
    void shutdown_blocksCompletionUntilSenderCancellationFinishes_andShutsDownSchedulerAfterward() throws Exception {
        AtomicInteger cancelCalls = new AtomicInteger();
        CountDownLatch cancelEntered = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CancellableTelegramDeliverySender fake = new CancellableTelegramDeliverySender() {
            @Override
            public @NotNull CompletableFuture<Void> deliver(@NotNull String chatId, @NotNull String text) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void cancelAllPending() {
                cancelCalls.incrementAndGet();
                cancelEntered.countDown();
                awaitQuietly(releaseCancel);
            }
        };

        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("123"));
        yaml.set("queue.shutdown-timeout-seconds", 0);
        TelegramConfig config = new TelegramConfig(yaml);
        client = new TelegramClient(config, scheduler, fake);

        CompletableFuture<Void> shutdownFuture = client.shutdown();

        assertThat(cancelEntered.await(4, TimeUnit.SECONDS)).isTrue();
        assertThat(shutdownFuture).isNotDone();
        assertThat(scheduler.isShutdown()).isFalse();

        releaseCancel.countDown();
        shutdownFuture.get(4, TimeUnit.SECONDS);

        assertThat(cancelCalls.get()).isEqualTo(1);
        assertThat(scheduler.awaitTermination(4, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @Timeout(5)
    void shutdown_forced_resolvesQueuesAfterSenderCancellationCompletes() throws Exception {
        CountDownLatch cancelEntered = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        CancellableTelegramDeliverySender fake = getFake(cancelEntered, releaseCancel);

        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("1", "2"));
        yaml.set("queue.shutdown-timeout-seconds", 0);
        TelegramConfig config = new TelegramConfig(yaml);
        client = new TelegramClient(config, Executors.newSingleThreadScheduledExecutor(), fake);

        CompletableFuture<Void> broadcast = client.sendMessage("hello");
        CompletableFuture<Void> shutdownFuture = client.shutdown();

        assertThat(cancelEntered.await(4, TimeUnit.SECONDS)).isTrue();
        assertThat(broadcast).isNotDone();
        assertThat(shutdownFuture).isNotDone();

        releaseCancel.countDown();
        shutdownFuture.get(4, TimeUnit.SECONDS);

        assertThat(broadcast).isCompletedExceptionally();
        assertThat(client.aggregateQueueStatistics().depth()).isZero();
    }

    @Test
    @Timeout(5)
    void shutdown_forcedByTimeout_stillTerminatesTheSchedulerAfterward() throws Exception {
        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("123"));
        yaml.set("queue.shutdown-timeout-seconds", 0);
        TelegramConfig config = new TelegramConfig(yaml);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CompletableFuture<HttpResponse<String>> neverCompletes = new CompletableFuture<>();
        TelegramApiTransport transport = new TelegramApiTransport(config, "http://unused", request -> neverCompletes);
        client = new TelegramClient(config, scheduler, new RetryingTelegramSender(transport, scheduler));

        client.sendMessage("hello");
        client.shutdown().get(4, TimeUnit.SECONDS);

        assertThat(scheduler.awaitTermination(4, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @Timeout(5)
    void shutdown_withMaxValueTimeoutSeconds_neverThrowsAndCompletesOnGracefulDrain() throws Exception {
        startServer(attempt -> new StubResponse(200, "{\"ok\":true}"));
        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("123"));
        yaml.set("queue.shutdown-timeout-seconds", Long.MAX_VALUE);
        TelegramConfig config = new TelegramConfig(yaml);
        client = new TelegramClient(config, "http://localhost:" + server.getAddress().getPort());

        client.sendMessage("hello").get(4, TimeUnit.SECONDS);

        client.shutdown().get(4, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(5)
    void shutdown_whenTimeoutSchedulingThrows_forcesShutdownImmediatelyAndStillCompletes() throws Exception {
        CompletableFuture<Void> stuck = new CompletableFuture<>();
        CancellableTelegramDeliverySender fake = new CancellableTelegramDeliverySender() {
            @Override
            public @NotNull CompletableFuture<Void> deliver(@NotNull String chatId, @NotNull String text) {
                return stuck;
            }

            @Override
            public void cancelAllPending() {
                stuck.completeExceptionally(new CancellationException("delivery cancelled"));
            }
        };

        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("123"));
        TelegramConfig config = new TelegramConfig(yaml);
        ThrowingOnScheduleScheduler scheduler = new ThrowingOnScheduleScheduler();
        client = new TelegramClient(config, scheduler, fake);

        CompletableFuture<Void> broadcast = client.sendMessage("hello");
        CompletableFuture<Void> shutdownFuture = client.shutdown();

        shutdownFuture.get(4, TimeUnit.SECONDS);
        assertThatThrownBy(() -> broadcast.get(1, TimeUnit.SECONDS)).cause().isInstanceOf(CancellationException.class);

        CompletableFuture<Void> repeated = client.shutdown();
        assertThat(repeated).isSameAs(shutdownFuture);
        repeated.get(1, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(5)
    void sendMessage_afterShutdownAcceptanceTransitionCompletes_touchesNoConfiguredQueue() throws Exception {
        AtomicInteger deliverCalls = new AtomicInteger();
        CancellableTelegramDeliverySender fake = new CancellableTelegramDeliverySender() {
            @Override
            public @NotNull CompletableFuture<Void> deliver(@NotNull String chatId, @NotNull String text) {
                deliverCalls.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void cancelAllPending() {
            }
        };

        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("1", "2"));
        yaml.set("queue.shutdown-timeout-seconds", 0);
        TelegramConfig config = new TelegramConfig(yaml);
        client = new TelegramClient(config, Executors.newSingleThreadScheduledExecutor(), fake);

        client.shutdown().get(3, TimeUnit.SECONDS);

        CompletableFuture<Void> rejected = client.sendMessage("too late");

        assertThatThrownBy(() -> rejected.get(1, TimeUnit.SECONDS))
                .cause().isInstanceOf(TelegramClientShuttingDownException.class);
        assertThat(deliverCalls.get()).isZero();
        assertThat(client.queueStatistics().get("1")).isEqualTo(new QueueStatistics(100, 0, 0));
        assertThat(client.queueStatistics().get("2")).isEqualTo(new QueueStatistics(100, 0, 0));
    }

    private @NotNull TelegramClient clientWithSender(@NotNull TelegramHttpSender sender) {
        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("123"));
        TelegramConfig config = new TelegramConfig(yaml);
        return new TelegramClient(config, "http://unused", sender);
    }

    private @NotNull TelegramClient clientWithSenderAndShutdownTimeout(
            @NotNull TelegramHttpSender sender, @NotNull Duration shutdownTimeout
    ) {
        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("123"));
        yaml.set("queue.shutdown-timeout-seconds", shutdownTimeout.toSeconds());
        TelegramConfig config = new TelegramConfig(yaml);
        return new TelegramClient(config, "http://unused", sender);
    }

    private record StubResponse(int status, String body) {

    }

    private static final class ThrowingOnScheduleScheduler extends AbstractExecutorService
            implements ScheduledExecutorService {

        private final ScheduledExecutorService delegate = Executors.newSingleThreadScheduledExecutor();

        @Override
        public ScheduledFuture<?> schedule(@NotNull Runnable command, long delay, @NotNull TimeUnit unit) {
            throw new RejectedExecutionException("simulated timeout scheduling failure");
        }

        @Override
        public <V> ScheduledFuture<V> schedule(@NotNull Callable<V> callable, long delay, @NotNull TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                @NotNull Runnable command, long initialDelay, long period, @NotNull TimeUnit unit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                @NotNull Runnable command, long initialDelay, long delay, @NotNull TimeUnit unit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public @NotNull List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(@NotNull Runnable command) {
            delegate.execute(command);
        }
    }
}