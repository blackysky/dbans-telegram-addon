package de.silke.dbans.telegram.client;

import org.jetbrains.annotations.NotNull;

public final class TelegramClientShuttingDownException extends RuntimeException {

    public TelegramClientShuttingDownException(@NotNull String message) {
        super(message);
    }
}