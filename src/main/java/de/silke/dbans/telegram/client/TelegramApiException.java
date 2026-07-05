package de.silke.dbans.telegram.client;

import lombok.Getter;

final class TelegramApiException extends RuntimeException {

    @Getter
    private final int statusCode;

    TelegramApiException(int statusCode) {
        super("Telegram API request failed with HTTP status " + statusCode);
        this.statusCode = statusCode;
    }
}