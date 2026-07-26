package de.silke.dbans.telegram.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramHttpResponseClassifierTest {

    @Test
    void status200_isSuccess() {
        TelegramHttpResponseClassifier.Result result = TelegramHttpResponseClassifier.classify(200, "{\"ok\":true}");

        assertThat(result.classification()).isEqualTo(TelegramHttpResponseClassifier.Classification.SUCCESS);
        assertThat(result.statusCode()).isEqualTo(200);
    }

    @Test
    void status429_isRateLimited() {
        TelegramHttpResponseClassifier.Result result = TelegramHttpResponseClassifier.classify(
                429, "{\"parameters\":{\"retry_after\":5}}");

        assertThat(result.classification()).isEqualTo(TelegramHttpResponseClassifier.Classification.RATE_LIMITED);
        assertThat(result.statusCode()).isEqualTo(429);
    }

    @Test
    void validRetryAfter_isParsed() {
        TelegramHttpResponseClassifier.Result result = TelegramHttpResponseClassifier.classify(
                429, "{\"parameters\":{\"retry_after\":17}}");

        assertThat(result.retryAfterSeconds()).isEqualTo(17);
    }

    @Test
    void missingParameters_usesDefaultRetryDelay() {
        TelegramHttpResponseClassifier.Result result = TelegramHttpResponseClassifier.classify(429, "{\"ok\":false}");

        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    void missingRetryAfterField_usesDefaultRetryDelay() {
        TelegramHttpResponseClassifier.Result result = TelegramHttpResponseClassifier.classify(
                429, "{\"parameters\":{}}");

        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    void malformedJson_usesDefaultRetryDelay() {
        TelegramHttpResponseClassifier.Result result = TelegramHttpResponseClassifier.classify(429, "not json at all");

        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    void statuses500_502_503_504_areTemporaryFailures() {
        for (int status : new int[]{500, 502, 503, 504}) {
            TelegramHttpResponseClassifier.Result result = TelegramHttpResponseClassifier.classify(status, "{}");
            assertThat(result.classification())
                    .as("status %d", status)
                    .isEqualTo(TelegramHttpResponseClassifier.Classification.TEMPORARY_FAILURE);
        }
    }

    @Test
    void otherClientAndServerStatuses_arePermanentFailures() {
        for (int status : new int[]{400, 401, 403, 404, 501, 505}) {
            TelegramHttpResponseClassifier.Result result = TelegramHttpResponseClassifier.classify(status, "{}");
            assertThat(result.classification())
                    .as("status %d", status)
                    .isEqualTo(TelegramHttpResponseClassifier.Classification.PERMANENT_FAILURE);
        }
    }

    @Test
    void emptyResponseBody_isHandledWithoutThrowing() {
        TelegramHttpResponseClassifier.Result result = TelegramHttpResponseClassifier.classify(429, "");

        assertThat(result.classification()).isEqualTo(TelegramHttpResponseClassifier.Classification.RATE_LIMITED);
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    void unexpectedJsonStructure_isHandledWithoutThrowing() {
        TelegramHttpResponseClassifier.Result result = TelegramHttpResponseClassifier.classify(429, "[1, 2, 3]");

        assertThat(result.classification()).isEqualTo(TelegramHttpResponseClassifier.Classification.RATE_LIMITED);
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    void negativeRetryAfter_fallsBackToDefaultRatherThanGoingNegative() {
        TelegramHttpResponseClassifier.Result result = TelegramHttpResponseClassifier.classify(
                429, "{\"parameters\":{\"retry_after\":-5}}");

        assertThat(result.retryAfterSeconds()).isEqualTo(30);
        assertThat(result.retryAfterSeconds()).isNotNegative();
    }

    @Test
    void excessivelyLargeRetryAfter_isCappedRatherThanTrustedVerbatim() {
        TelegramHttpResponseClassifier.Result result = TelegramHttpResponseClassifier.classify(
                429, "{\"parameters\":{\"retry_after\":999999999}}");

        assertThat(result.retryAfterSeconds()).isEqualTo(3600);
    }
}