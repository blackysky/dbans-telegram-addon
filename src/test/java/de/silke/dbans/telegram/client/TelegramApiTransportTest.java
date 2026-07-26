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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramApiTransportTest {

    private static @NotNull TelegramConfig configWithToken(@NotNull String token) {
        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("client.token", token);
        yaml.set("client.chat-ids", List.of("123"));
        return new TelegramConfig(yaml);
    }

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

    private static @NotNull Throwable catchFutureFailure(@NotNull CompletableFuture<?> future) {
        try {
            future.get();
            throw new AssertionError("expected future to fail");
        } catch (Exception e) {
            return e.getCause() != null ? e.getCause() : e;
        }
    }

    private static @NotNull String bodyOf(@NotNull HttpRequest request) {
        AtomicReference<String> body = new AtomicReference<>("");
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                body.set(body.get() + new String(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });
        return body.get();
    }

    @Test
    @Timeout(5)
    void send_formEncodesChatIdAndText() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        TelegramApiTransport transport = new TelegramApiTransport(configWithToken("test-token"), "http://api.example", request -> {
            captured.set(request);
            return CompletableFuture.completedFuture(fakeResponse(200, "{\"ok\":true}"));
        });

        transport.send("123", "hello world & friends");

        String body = bodyOf(captured.get());
        assertThat(body).contains("chat_id=123");
        assertThat(body).contains("text=hello+world+%26+friends");
    }

    @Test
    @Timeout(5)
    void send_tokenAppearsOnlyInTheRequestUri() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        String token = "my-secret-token";
        TelegramApiTransport transport = new TelegramApiTransport(configWithToken(token), "http://api.example", request -> {
            captured.set(request);
            return CompletableFuture.completedFuture(fakeResponse(200, "{\"ok\":true}"));
        });

        transport.send("123", "hello");

        HttpRequest request = captured.get();
        assertThat(request.uri().toString()).contains(token);
        assertThat(bodyOf(request)).doesNotContain(token);
    }

    @Test
    @Timeout(5)
    void send_usesTheConfiguredBaseUrlAndSendMessageEndpoint() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        TelegramApiTransport transport = new TelegramApiTransport(configWithToken("test-token"), "http://api.example", request -> {
            captured.set(request);
            return CompletableFuture.completedFuture(fakeResponse(200, "{\"ok\":true}"));
        });

        transport.send("123", "hello");

        assertThat(captured.get().uri().toString()).isEqualTo("http://api.example/bottest-token/sendMessage");
    }

    @Test
    @Timeout(5)
    void send_usesFormUrlEncodedContentType() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        TelegramApiTransport transport = new TelegramApiTransport(configWithToken("test-token"), "http://api.example", request -> {
            captured.set(request);
            return CompletableFuture.completedFuture(fakeResponse(200, "{\"ok\":true}"));
        });

        transport.send("123", "hello");

        assertThat(captured.get().headers().firstValue("Content-Type"))
                .contains("application/x-www-form-urlencoded");
    }

    @Test
    @Timeout(5)
    void send_usesPost() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        TelegramApiTransport transport = new TelegramApiTransport(configWithToken("test-token"), "http://api.example", request -> {
            captured.set(request);
            return CompletableFuture.completedFuture(fakeResponse(200, "{\"ok\":true}"));
        });

        transport.send("123", "hello");

        assertThat(captured.get().method()).isEqualTo("POST");
    }

    @Test
    @Timeout(5)
    void send_synchronousHttpSenderFailure_becomesAFailedFuture() {
        RuntimeException boom = new RuntimeException("sender exploded");
        TelegramApiTransport transport = new TelegramApiTransport(configWithToken("test-token"), "http://api.example", request -> {
            throw boom;
        });

        CompletableFuture<HttpResponse<String>> result = transport.send("123", "hello");

        assertThat(result).isCompletedExceptionally();
        assertThatThrownBy(result::get)
                .cause().isInstanceOf(TelegramRequestPreparationException.class)
                .cause().isSameAs(boom);
    }

    @Test
    @Timeout(5)
    void send_requestConstructionFailure_becomesTheDedicatedPreparationFailure() {
        TelegramApiTransport transport = new TelegramApiTransport(
                configWithToken("bad token with spaces"), "http://api.example",
                request -> CompletableFuture.completedFuture(fakeResponse(200, "{\"ok\":true}")));

        CompletableFuture<HttpResponse<String>> result = transport.send("123", "hello");

        assertThat(result).isCompletedExceptionally();
        assertThatThrownBy(result::get).cause().isInstanceOf(TelegramRequestPreparationException.class);
    }

    @Test
    @Timeout(5)
    void send_requestConstructionFailure_neverPlacesTheTokenInAnyExceptionMessage() {
        String token = "bad token with spaces";
        TelegramApiTransport transport = new TelegramApiTransport(
                configWithToken(token), "http://api.example",
                request -> CompletableFuture.completedFuture(fakeResponse(200, "{\"ok\":true}")));

        CompletableFuture<HttpResponse<String>> result = transport.send("123", "hello");

        Throwable failure = catchFutureFailure(result);
        StringBuilder allMessages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            allMessages.append(current).append(current.getMessage());
        }
        assertThat(allMessages.toString()).doesNotContain(token);
    }

    @Test
    void constructor_rejectsNullArguments() {
        TelegramConfig config = configWithToken("test-token");
        assertThatThrownBy(() -> new TelegramApiTransport(null, "http://api.example", request -> null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TelegramApiTransport(config, null, request -> null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TelegramApiTransport(config, "http://api.example", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void send_rejectsNullArguments() {
        TelegramApiTransport transport = new TelegramApiTransport(
                configWithToken("test-token"), "http://api.example",
                request -> CompletableFuture.completedFuture(fakeResponse(200, "{\"ok\":true}")));

        assertThatThrownBy(() -> transport.send(null, "hello")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> transport.send("123", null)).isInstanceOf(NullPointerException.class);
    }
}