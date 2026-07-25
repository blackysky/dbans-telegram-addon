package de.silke.dbans.telegram.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

@UtilityClass
final class TelegramHttpResponseClassifier {

    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(500, 502, 503, 504);
    private static final int DEFAULT_RETRY_AFTER_SECONDS = 30;

    @Contract("_, _ -> new")
    static @NotNull Result classify(int statusCode, @NotNull String body) {
        if (statusCode == 200) {
            return new Result(Classification.SUCCESS, statusCode, 0);
        }
        if (statusCode == 429) {
            return new Result(Classification.RATE_LIMITED, statusCode, parseRetryAfterSeconds(body));
        }
        if (RETRYABLE_STATUS_CODES.contains(statusCode)) {
            return new Result(Classification.TEMPORARY_FAILURE, statusCode, 0);
        }
        return new Result(Classification.PERMANENT_FAILURE, statusCode, 0);
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
        } catch (RuntimeException ignored) {
        }
        return DEFAULT_RETRY_AFTER_SECONDS;
    }

    enum Classification {
        SUCCESS,
        RATE_LIMITED,
        TEMPORARY_FAILURE,
        PERMANENT_FAILURE
    }

    record Result(@NotNull Classification classification, int statusCode, int retryAfterSeconds) {

    }
}