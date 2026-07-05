package de.silke.dbans.telegram.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.silke.dbans.telegram.config.TelegramConfig;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TelegramClient {

    private static final Logger log = Logger.getLogger("dbans-telegram-addon");
    private static final String DEFAULT_API_BASE_URL = "https://api.telegram.org";
    private static final int MAX_RETRIES = 3;
    private static final Duration MIN_SEND_INTERVAL = Duration.ofSeconds(1);
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(500, 502, 503, 504);
    private static final HttpClient DEFAULT_HTTP_CLIENT = createDefaultHttpClient();

    private final TelegramConfig config;
    private final String apiBaseUrl;
    private final TelegramHttpSender httpSender;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, CompletableFuture<Void>> chatQueues = new ConcurrentHashMap<>();

    public TelegramClient(@NotNull TelegramConfig config) {
        this(config, DEFAULT_API_BASE_URL);
    }

    TelegramClient(@NotNull TelegramConfig config, @NotNull String apiBaseUrl) {
        this(config, apiBaseUrl, defaultHttpSender());
    }

    TelegramClient(@NotNull TelegramConfig config, @NotNull String apiBaseUrl,
                   @NotNull TelegramHttpSender httpSender
    ) {
        this.config = config;
        this.apiBaseUrl = apiBaseUrl;
        this.httpSender = httpSender;
    }

    @Contract(pure = true)
    private static @NotNull TelegramHttpSender defaultHttpSender() {
        return request -> DEFAULT_HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());
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
                if (retryAfter != null) return retryAfter.getAsInt();
            }
        } catch (Exception ignored) {
        }
        return 30;
    }

    @SuppressWarnings("UnusedReturnValue")
    public @NotNull CompletableFuture<Void> sendMessage(@NotNull String text) {
        return CompletableFuture.allOf(
                config.getChatIds().stream()
                      .map(chatId -> enqueue(chatId, text))
                      .toArray(CompletableFuture[]::new)
        );
    }

    private @NotNull CompletableFuture<Void> enqueue(@NotNull String chatId, @NotNull String text) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        chatQueues.compute(chatId, (id, previous) ->
                buildMessageQueue(id, text, result, previous));
        return result;
    }

    private @NotNull CompletableFuture<Void> buildMessageQueue(
            @NotNull String chatId, @NotNull String text, @NotNull CompletableFuture<Void> result,
            @Nullable CompletableFuture<Void> previous
    ) {
        CompletableFuture<Void> previousOrDone = previous != null ? previous : CompletableFuture.completedFuture(null);
        CompletableFuture<Void> sent = previousOrDone.thenCompose(v -> pacedSend(chatId, text));
        sent.whenComplete((v, ex) -> completeResult(result, ex));
        return sent.exceptionally(ex -> null);
    }

    private void completeResult(@NotNull CompletableFuture<Void> result, @Nullable Throwable ex) {
        if (ex != null) {
            result.completeExceptionally(ex);
        } else {
            result.complete(null);
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
        scheduler.schedule(() -> future.complete(null),
                           TelegramClient.MIN_SEND_INTERVAL.toMillis(),
                           TimeUnit.MILLISECONDS
        );
        return future;
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
                               + "; retrying (attempt " + (attempt + 1) + "/" + MAX_RETRIES + ")", ex);
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
                log.warning("Telegram rate limit hit; message to " + chatId + " dropped after " + MAX_RETRIES + " retries");
                return CompletableFuture.failedFuture(new TelegramApiException(status));
            }

            int retryAfter = parseRetryAfterSeconds(response.body());
            log.warning("Telegram rate limit for " + chatId + " (retry_after=" + retryAfter + "s); retrying in "
                        + retryAfter + "s (attempt " + (attempt + 1) + "/" + MAX_RETRIES + ")");
            return scheduleRetry(chatId, text, attempt, retryAfter);
        }

        if (RETRYABLE_STATUS_CODES.contains(status)) {
            if (attempt >= MAX_RETRIES) {
                log.warning("Telegram server error " + status + "; message to " + chatId + " dropped after " + MAX_RETRIES + " retries");
                return CompletableFuture.failedFuture(new TelegramApiException(status));
            }
            log.warning("Telegram server error " + status + " for " + chatId + "; retrying "
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
        scheduler.schedule(
                () -> sendMessage(chatId, text, attempt + 1)
                        .whenComplete((v, ex) -> {
                            if (ex != null) future.completeExceptionally(ex);
                            else future.complete(null);
                        }),
                delaySeconds, TimeUnit.SECONDS
        );
        return future;
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
