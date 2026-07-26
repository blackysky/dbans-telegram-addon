package de.silke.dbans.telegram.client;

import org.jetbrains.annotations.NotNull;

final class TelegramRequestPreparationException extends RuntimeException {

    TelegramRequestPreparationException(@NotNull String message, @NotNull Throwable cause) {
        super(message, cause);
    }
}