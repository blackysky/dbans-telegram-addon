package de.silke.dbans.telegram.client;

import org.jetbrains.annotations.NotNull;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
interface TelegramHttpSender {

    @NotNull CompletableFuture<HttpResponse<String>> send(@NotNull HttpRequest request);
}