package de.silke.dbans.telegram.client;

import de.silke.dbans.telegram.config.TelegramConfig;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private boolean accepting = true;
    private CompletableFuture<Void> shutdownFuture;

    @TestOnly
    private volatile Runnable afterAcceptanceCheckHookForTesting = () -> {
    };

    @TestOnly
    private volatile Runnable beforeShutdownTransitionHookForTesting = () -> {
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
        List<QueueSubmission> submissions;
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            if (!accepting) {
                return CompletableFuture.failedFuture(clientShutdownException());
            }
            afterAcceptanceCheckHookForTesting.run();
            submissions = config.getChatIds().stream()
                                .map(chatQueues::get)
                                .map(queue -> new QueueSubmission(queue, queue.submit(text)))
                                .toList();
        } finally {
            readLock.unlock();
        }

        for (QueueSubmission submission : submissions) {
            if (submission.outcome().shouldActivate()) {
                submission.queue().startNext();
            }
        }

        return CompletableFuture.allOf(
                submissions.stream().map(submission -> submission.outcome().future()).toArray(CompletableFuture[]::new)
        );
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
        Lock writeLock = lifecycleLock.writeLock();
        writeLock.lock();
        CompletableFuture<Void> future;
        try {
            beforeShutdownTransitionHookForTesting.run();
            if (shutdownFuture != null) {
                return shutdownFuture;
            }
            future = new CompletableFuture<>();
            shutdownFuture = future;
            accepting = false;
            chatQueues.values().forEach(ChatDeliveryQueue::stopAccepting);
        } finally {
            writeLock.unlock();
        }

        beginTermination(future);
        return future;
    }

    private void beginTermination(@NotNull CompletableFuture<Void> future) {
        CompletableFuture<Void> drained = CompletableFuture.allOf(
                chatQueues.values().stream().map(ChatDeliveryQueue::drain).toArray(CompletableFuture[]::new)
        );

        AtomicBoolean terminated = new AtomicBoolean();
        ScheduledFuture<?> timeoutTask = scheduleForceShutdown(future, terminated);
        drained.whenCompleteAsync((v, ex) -> completeTermination(future, terminated, timeoutTask, false), scheduler);
    }

    private @Nullable ScheduledFuture<?> scheduleForceShutdown(
            @NotNull CompletableFuture<Void> future, @NotNull AtomicBoolean terminated
    ) {
        try {
            long timeoutSeconds = config.queue().shutdownTimeout().toSeconds();
            return scheduler.schedule(() -> completeTermination(future, terminated, null, true),
                                      timeoutSeconds, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            log.warning("Failed to schedule the forced Telegram shutdown timeout (" + e.getClass().getSimpleName()
                        + "). Forcing shutdown immediately");
            completeTermination(future, terminated, null, true);
            return null;
        }
    }

    private void completeTermination(
            @NotNull CompletableFuture<Void> future, @NotNull AtomicBoolean terminated,
            @Nullable ScheduledFuture<?> timeoutTaskToCancel, boolean forced
    ) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        if (timeoutTaskToCancel != null) {
            timeoutTaskToCancel.cancel(false);
        }
        sender.cancelAllPending();
        int cancelledItems = chatQueues.values().stream().mapToInt(ChatDeliveryQueue::forceCancel).sum();
        if (forced && cancelledItems > 0) {
            log.warning("Telegram graceful shutdown timed out. Forcibly cancelled " + cancelledItems + " pending deliveries");
        }
        scheduler.shutdownNow();
        future.complete(null);
    }

    @TestOnly
    void setAfterAcceptanceCheckHookForTesting(@NotNull Runnable hook) {
        this.afterAcceptanceCheckHookForTesting = Objects.requireNonNull(hook, "hook");
    }

    @TestOnly
    void setBeforeShutdownTransitionHookForTesting(@NotNull Runnable hook) {
        this.beforeShutdownTransitionHookForTesting = Objects.requireNonNull(hook, "hook");
    }

    @TestOnly
    boolean hasThreadsQueuedForLifecycleLockForTesting() {
        return lifecycleLock.hasQueuedThreads();
    }

    private record QueueSubmission(@NotNull ChatDeliveryQueue queue, @NotNull ChatDeliveryQueue.SubmitOutcome outcome) {

    }
}