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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("ALL")
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
    void failsAfterNetworkRetryLimit() throws Exception {
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

    private @NotNull TelegramClient clientWithSender(@NotNull TelegramHttpSender sender) {
        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", "test-token");
        yaml.set("client.chat-ids", List.of("123"));
        TelegramConfig config = new TelegramConfig(yaml);
        return new TelegramClient(config, "http://unused", sender);
    }

    private record StubResponse(int status, String body) {

    }
}
