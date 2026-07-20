package de.silke.dbans.telegram.client;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

interface TelegramDeliverySender {

    @NotNull CompletableFuture<Void> deliver(@NotNull String chatId, @NotNull String text);

    void cancelAllPending();

}