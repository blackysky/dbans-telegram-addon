package de.silke.dbans.telegram.client;

import org.jetbrains.annotations.NotNull;

final class TelegramQueueFullException extends RuntimeException {

    TelegramQueueFullException(@NotNull String chatId, int capacity) {
        super("Telegram delivery queue for chat " + chatId + " is full (capacity=" + capacity + ")");
    }

}