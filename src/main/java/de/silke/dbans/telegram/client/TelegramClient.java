package de.silke.dbans.telegram.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.silke.dbans.telegram.config.TelegramConfig;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@RequiredArgsConstructor
public class TelegramClient {

    private static final Logger log = Logger.getLogger("dbans-telegram-addon.TelegramClient");
    private static final int MAX_RETRIES = 3;

    private final TelegramConfig config;
    private final HttpClient httpClient = HttpClient.newBuilder()
                                                    .connectTimeout(Duration.ofSeconds(5))
                                                    .build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private static int parseRetryAfterSeconds(@NotNull String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject params = root.getAsJsonObject("parameters");
            if (params != null) {
                JsonElement retryAfter = params.get("retry_after");
                if (retryAfter != null) return retryAfter.getAsInt();
            }
        } catch (Exception ignored) {
        }
        return 30;
    }

    @SuppressWarnings("UnusedReturnValue")
    public @NotNull CompletableFuture<Void> sendMessage(@NotNull String text) {
        return sendMessage(text, 0);
    }

    private @NotNull CompletableFuture<Void> sendMessage(@NotNull String text, int attempt) {
        String url = "https://api.telegram.org/bot" + config.getToken() + "/sendMessage";
        String body = "chat_id=" + URLEncoder.encode(config.getChatId(), StandardCharsets.UTF_8)
                      + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                                         .uri(URI.create(url))
                                         .header("Content-Type", "application/x-www-form-urlencoded")
                                         .timeout(Duration.ofSeconds(10))
                                         .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                                         .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                         .thenCompose(response -> handleResponse(text, response, attempt))
                         .exceptionally(ex -> {
                             log.log(Level.SEVERE, "Failed to send Telegram message", ex);
                             return null;
                         });
    }

    private @NotNull CompletableFuture<Void> handleResponse(
            @NotNull String text,
            @NotNull HttpResponse<String> response,
            int attempt
    ) {
        int status = response.statusCode();
        if (status == 429) {
            if (attempt >= MAX_RETRIES) {
                log.warning("Telegram rate limit hit; message dropped after " + MAX_RETRIES + " retries");
                return CompletableFuture.completedFuture(null);
            }
            int retryAfter = parseRetryAfterSeconds(response.body());
            log.warning("Telegram rate limit (retry_after=" + retryAfter + "s); retrying in "
                        + retryAfter + "s (attempt " + (attempt + 1) + "/" + MAX_RETRIES + ").");

            CompletableFuture<Void> future = new CompletableFuture<>();
            scheduler.schedule(
                    () -> sendMessage(text, attempt + 1)
                            .whenComplete((v, ex) -> {
                                if (ex != null) {
                                    future.completeExceptionally(ex);
                                } else {
                                    future.complete(null);
                                }
                            }),
                    retryAfter, TimeUnit.SECONDS
            );
            return future;
        } else if (status == 200) {
            return CompletableFuture.completedFuture(null);
        } else {
            log.warning("Telegram API returned unexpected status " + status + ": " + response.body());
            return CompletableFuture.completedFuture(null);
        }
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
