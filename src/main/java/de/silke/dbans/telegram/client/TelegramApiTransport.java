package de.silke.dbans.telegram.client;

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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class TelegramApiTransport {

    private final TelegramConfig config;
    private final String apiBaseUrl;
    private final TelegramHttpSender httpSender;

    TelegramApiTransport(@NotNull TelegramConfig config, @NotNull String apiBaseUrl,
                         @NotNull TelegramHttpSender httpSender
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.apiBaseUrl = Objects.requireNonNull(apiBaseUrl, "apiBaseUrl");
        this.httpSender = Objects.requireNonNull(httpSender, "httpSender");
    }

    @Contract(pure = true)
    static @NotNull TelegramHttpSender defaultHttpSender() {
        return request -> HttpClientHolder.INSTANCE.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private static @NotNull HttpClient createDefaultHttpClient() {
        return HttpClient.newBuilder()
                         .connectTimeout(Duration.ofSeconds(5))
                         .build();
    }

    @NotNull CompletableFuture<HttpResponse<String>> send(@NotNull String chatId, @NotNull String text) {
        try {
            HttpRequest request = buildRequest(chatId, text);
            return httpSender.send(request);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private @NotNull HttpRequest buildRequest(@NotNull String chatId, @NotNull String text) {
        String url = apiBaseUrl + "/bot" + config.getToken() + "/sendMessage";
        String body = "chat_id=" + URLEncoder.encode(chatId, StandardCharsets.UTF_8)
                      + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);

        return HttpRequest.newBuilder()
                          .uri(URI.create(url))
                          .header("Content-Type", "application/x-www-form-urlencoded")
                          .timeout(Duration.ofSeconds(10))
                          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                          .build();
    }

    @UtilityClass
    private static final class HttpClientHolder {

        private static final HttpClient INSTANCE = createDefaultHttpClient();
    }
}