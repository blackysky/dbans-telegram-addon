package de.silke.dbans.telegram.client;

import de.silke.dbans.telegram.config.TelegramConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class TelegramClient {

    private static final Logger log = Logger.getLogger("dbans-telegram-addon");
    private static final String DEFAULT_API_BASE_URL = "https://api.telegram.org";

    private final TelegramConfig config;
    private final TelegramDeliverySender sender;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ChatDeliveryQueue> chatQueues;
    private final AtomicReference<CompletableFuture<Void>> shutdownFuture = new AtomicReference<>();

    public TelegramClient(@NotNull TelegramConfig config) {
        this(config, DEFAULT_API_BASE_URL);
    }

    TelegramClient(@NotNull TelegramConfig config, @NotNull String apiBaseUrl) {
        this(config, apiBaseUrl, RetryingTelegramSender.defaultHttpSender());
    }

    TelegramClient(@NotNull TelegramConfig config, @NotNull String apiBaseUrl, @NotNull TelegramHttpSender httpSender) {
        this(config, apiBaseUrl, httpSender, Executors.newSingleThreadScheduledExecutor(TelegramClient::newDaemonThread));
    }

    private TelegramClient(@NotNull TelegramConfig config, @NotNull String apiBaseUrl,
                           @NotNull TelegramHttpSender httpSender, @NotNull ScheduledExecutorService scheduler
    ) {
        this(config, scheduler, new RetryingTelegramSender(config, apiBaseUrl, httpSender, scheduler));
    }

    TelegramClient(@NotNull TelegramConfig config, @NotNull ScheduledExecutorService scheduler,
                   @NotNull TelegramDeliverySender sender
    ) {
        this.config = config;
        this.scheduler = scheduler;
        this.sender = sender;
        this.chatQueues = config.getChatIds().stream().collect(Collectors.toUnmodifiableMap(
                Function.identity(),
                chatId -> new ChatDeliveryQueue(chatId, config.queue().capacity(), config.queue().overflowPolicy(), sender)
        ));
    }

    private static @NotNull Thread newDaemonThread(@NotNull Runnable runnable) {
        Thread thread = new Thread(runnable, "dbans-telegram-scheduler");
        thread.setDaemon(true);
        return thread;
    }

    @SuppressWarnings("UnusedReturnValue")
    public @NotNull CompletableFuture<Void> sendMessage(@NotNull String text) {
        CompletableFuture<?>[] futures = config.getChatIds().stream()
                                               .map(chatId -> chatQueues.get(chatId).submit(text))
                                               .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    @NotNull Map<String, QueueStatistics> queueStatistics() {
        return chatQueues.entrySet().stream()
                         .collect(
                                 Collectors.toUnmodifiableMap(
                                         Map.Entry::getKey,
                                         e -> e.getValue().statistics())
                         );
    }

    @NotNull QueueStatistics aggregateQueueStatistics() {
        int capacity = 0;
        int depth = 0;
        long dropped = 0;
        for (ChatDeliveryQueue queue : chatQueues.values()) {
            QueueStatistics stats = queue.statistics();
            capacity += stats.capacity();
            depth += stats.depth();
            dropped += stats.dropped();
        }
        return new QueueStatistics(capacity, depth, dropped);
    }

    @SuppressWarnings("UnusedReturnValue")
    public @NotNull CompletableFuture<Void> shutdown() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (!shutdownFuture.compareAndSet(null, future)) {
            return shutdownFuture.get();
        }

        chatQueues.values().forEach(ChatDeliveryQueue::stopAccepting);
        CompletableFuture<Void> drained = CompletableFuture.allOf(
                chatQueues.values().stream().map(ChatDeliveryQueue::drain).toArray(CompletableFuture[]::new)
        );

        ScheduledFuture<?> timeoutTask = scheduleForceShutdown();
        drained.whenComplete((v, ex) -> {
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            scheduler.shutdownNow();
            future.complete(null);
        });
        return future;
    }

    private @Nullable ScheduledFuture<?> scheduleForceShutdown() {
        long timeoutMillis = config.queue().shutdownTimeout().toMillis();
        try {
            return scheduler.schedule(this::forceShutdown, timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.warning("Failed to schedule forced Telegram shutdown, forcing immediately: " + e.getMessage());
            forceShutdown();
            return null;
        }
    }

    private void forceShutdown() {
        int cancelled = chatQueues.values().stream().mapToInt(ChatDeliveryQueue::forceCancel).sum();
        sender.cancelAllPending();
        if (cancelled > 0) {
            log.warning("Telegram graceful shutdown timed out. Forcibly cancelled " + cancelled + " pending " +
                        "deliveries");
        }
    }

}