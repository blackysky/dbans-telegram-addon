package de.silke.dbans.telegram.client;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class TelegramQueueFullException extends RuntimeException {

    private final String chatId;
    private final int capacity;

    TelegramQueueFullException(@NotNull String chatId, int capacity) {
        super("Telegram delivery queue for chat " + chatId + " is full (capacity=" + capacity + ")");
        this.chatId = Objects.requireNonNull(chatId, "chatId");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be greater than zero");
        }
        this.capacity = capacity;
    }

    @Contract(pure = true)
    public @NotNull String chatId() {
        return chatId;
    }

    @Contract(pure = true)
    public int capacity() {
        return capacity;
    }
}