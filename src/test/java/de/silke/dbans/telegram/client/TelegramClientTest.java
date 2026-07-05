package de.silke.dbans.telegram.client;

import com.sun.net.httpserver.HttpServer;
import de.silke.dbans.telegram.config.TelegramConfig;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramClientTest {

    private HttpServer server;
    private TelegramClient client;
    private AtomicInteger requestCount;

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
        future.get(9, TimeUnit.SECONDS);

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

        client.sendMessage("hello").get(4, TimeUnit.SECONDS);

        assertThat(requestCount.get()).isEqualTo(1);
    }

    private record StubResponse(int status, String body) {

    }
}
