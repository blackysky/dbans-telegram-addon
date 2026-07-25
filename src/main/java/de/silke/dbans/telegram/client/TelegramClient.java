package de.silke.dbans.telegram.client;

import de.silke.dbans.telegram.config.TelegramConfig;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class TelegramClient {

    private static final Logger log = Logger.getLogger("dbans-telegram-addon");
    private static final String DEFAULT_API_BASE_URL = "https://api.telegram.org";

    private final TelegramConfig config;
    private final CancellableTelegramDeliverySender sender;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ChatDeliveryQueue> chatQueues;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private final AtomicReference<CompletableFuture<Void>> shutdownFuture = new AtomicReference<>();
    private boolean accepting = true;

    /**
     * Runs while {@link #sendMessage(String)} still on read lock, after
     * acceptance check and before any queue is touched.
     */
    @TestOnly
    private volatile Runnable afterAcceptanceCheckHookForTesting = () -> {
    };

    public TelegramClient(@NotNull TelegramConfig config) {
        this(config, DEFAULT_API_BASE_URL);
    }

    TelegramClient(@NotNull TelegramConfig config, @NotNull String apiBaseUrl) {
        this(config, apiBaseUrl, TelegramApiTransport.defaultHttpSender());
    }

    TelegramClient(@NotNull TelegramConfig config, @NotNull String apiBaseUrl, @NotNull TelegramHttpSender httpSender) {
        this(config, apiBaseUrl, httpSender, Executors.newSingleThreadScheduledExecutor(TelegramClient::newDaemonThread));
    }

    private TelegramClient(@NotNull TelegramConfig config, @NotNull String apiBaseUrl,
                           @NotNull TelegramHttpSender httpSender, @NotNull ScheduledExecutorService scheduler
    ) {
        this(config, scheduler, new RetryingTelegramSender(new TelegramApiTransport(config, apiBaseUrl, httpSender), scheduler));
    }

    TelegramClient(@NotNull TelegramConfig config, @NotNull ScheduledExecutorService scheduler,
                   @NotNull CancellableTelegramDeliverySender sender
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.sender = Objects.requireNonNull(sender, "sender");
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

    @Contract(value = " -> new", pure = true)
    private static @NotNull TelegramClientShuttingDownException clientShutdownException() {
        return new TelegramClientShuttingDownException("TelegramClient is shutting down and no longer accepts messages");
    }

    @SuppressWarnings("UnusedReturnValue")
    public @NotNull CompletableFuture<Void> sendMessage(@NotNull String text) {
        Objects.requireNonNull(text, "text");
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            if (!accepting) {
                return CompletableFuture.failedFuture(clientShutdownException());
            }
            afterAcceptanceCheckHookForTesting.run();
            CompletableFuture<?>[] futures = config.getChatIds().stream()
                                                   .map(chatId -> chatQueues.get(chatId).submit(text))
                                                   .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(futures);
        } finally {
            readLock.unlock();
        }
    }

    // TODO: Make more efficient and use in status command
    public @NotNull Map<String, QueueStatistics> queueStatistics() {
        return chatQueues.entrySet().stream()
                         .collect(
                                 Collectors.toUnmodifiableMap(
                                         Map.Entry::getKey,
                                         e -> e.getValue().statistics())
                         );
    }

    // TODO: Use in status command
    public @NotNull QueueStatistics aggregateQueueStatistics() {
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

        Lock writeLock = lifecycleLock.writeLock();
        writeLock.lock();
        try {
            accepting = false;
            chatQueues.values().forEach(ChatDeliveryQueue::stopAccepting);
        } finally {
            writeLock.unlock();
        }

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

    @TestOnly
    void setAfterAcceptanceCheckHookForTesting(@NotNull Runnable hook) {
        this.afterAcceptanceCheckHookForTesting = Objects.requireNonNull(hook, "hook");
    }

    private void forceShutdown() {
        int cancelled = chatQueues.values().stream().mapToInt(ChatDeliveryQueue::forceCancel).sum();
        sender.cancelAllPending();
        if (cancelled > 0) {
            log.warning("Telegram graceful shutdown timed out. " +
                        "Forcibly cancelled " + cancelled + " pending deliveries");
        }
    }
}