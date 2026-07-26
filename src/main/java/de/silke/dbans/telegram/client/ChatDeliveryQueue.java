package de.silke.dbans.telegram.client;

import de.silke.dbans.telegram.config.QueueOverflowPolicy;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

final class ChatDeliveryQueue {

    private final Object lock = new Object();
    private final String chatId;
    private final int capacity;
    private final QueueOverflowPolicy overflowPolicy;
    private final TelegramDeliverySender sender;
    private final ArrayDeque<QueueItem> items = new ArrayDeque<>();
    private final List<CompletableFuture<Void>> drainWaiters = new ArrayList<>();
    private final AtomicLong droppedCount = new AtomicLong();
    private QueueState state = QueueState.ACCEPTING;
    private QueueItem activeItem;

    @TestOnly
    private volatile Runnable beforeInvokeDeliverHookForTesting = () -> {
    };

    ChatDeliveryQueue(@NotNull String chatId, int capacity, @NotNull QueueOverflowPolicy overflowPolicy,
                      @NotNull TelegramDeliverySender sender
    ) {
        this.chatId = Objects.requireNonNull(chatId, "chatId");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
        this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    @Contract(value = " -> new", pure = true)
    private static @NotNull TelegramClientShuttingDownException shutdownException() {
        return new TelegramClientShuttingDownException(
                "Telegram chat queue is shutting down and no longer accepts messages");
    }

    @Contract("_ -> new")
    @NotNull SubmitOutcome submit(@NotNull String text) {
        Objects.requireNonNull(text, "text");
        CompletableFuture<Void> future = new CompletableFuture<>();
        Throwable rejection = null;
        boolean shouldActivate = false;

        synchronized (lock) {
            if (state != QueueState.ACCEPTING) {
                rejection = shutdownException();
            } else if (items.size() >= capacity) {
                droppedCount.incrementAndGet();
                rejection = overflowFailure();
            } else {
                items.add(new QueueItem(text, future));
                shouldActivate = items.size() == 1;
            }
        }

        if (rejection != null) {
            future.completeExceptionally(rejection);
            return new SubmitOutcome(future, false);
        }
        return new SubmitOutcome(future, shouldActivate);
    }

    private @NotNull TelegramQueueFullException overflowFailure() {
        return switch (overflowPolicy) {
            case DROP_NEWEST -> new TelegramQueueFullException(chatId, capacity);
        };
    }

    void startNext() {
        QueueItem head;
        synchronized (lock) {
            if (state == QueueState.FORCIBLY_STOPPED) {
                return;
            }
            head = items.peek();
            if (head == null || head.future().isDone()) {
                return;
            }
            activeItem = head;
        }
        beforeInvokeDeliverHookForTesting.run();
        invokeDeliver(head);
    }

    private void invokeDeliver(@NotNull QueueItem item) {
        synchronized (lock) {
            if (state == QueueState.FORCIBLY_STOPPED || activeItem != item) {
                return;
            }
        }
        CompletableFuture<Void> deliveryFuture;
        try {
            deliveryFuture = sender.deliver(chatId, item.text());
        } catch (RuntimeException e) {
            onDeliveryComplete(item, e);
            return;
        }
        deliveryFuture.whenComplete((v, ex) -> onDeliveryComplete(item, ex));
    }

    private void onDeliveryComplete(@NotNull QueueItem item, @Nullable Throwable ex) {
        boolean idle = false;
        boolean advance = false;
        synchronized (lock) {
            if (activeItem == item && items.peek() == item) {
                items.poll();
                activeItem = null;
                idle = items.isEmpty();
                advance = !idle;
            }
        }
        completeResult(item.future(), ex);

        if (advance) {
            startNext();
        } else if (idle) {
            completeDrainWaiters();
        }
    }

    private void completeResult(@NotNull CompletableFuture<Void> future, @Nullable Throwable ex) {
        if (ex != null) {
            future.completeExceptionally(ex);
        } else {
            future.complete(null);
        }
    }

    @Contract(mutates = "this")
    void stopAccepting() {
        synchronized (lock) {
            if (state == QueueState.ACCEPTING) {
                state = QueueState.DRAINING;
            }
        }
    }

    @NotNull CompletableFuture<Void> drain() {
        synchronized (lock) {
            if (items.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> waiter = new CompletableFuture<>();
            drainWaiters.add(waiter);
            return waiter;
        }
    }

    int forceCancel() {
        List<QueueItem> remaining;
        synchronized (lock) {
            state = QueueState.FORCIBLY_STOPPED;
            remaining = List.copyOf(items);
            items.clear();
            activeItem = null;
        }
        for (QueueItem item : remaining) {
            item.future().completeExceptionally(
                    new CancellationException("Telegram delivery for chat " + chatId + " was cancelled by forced shutdown"));
        }
        completeDrainWaiters();
        return remaining.size();
    }

    private void completeDrainWaiters() {
        List<CompletableFuture<Void>> waiters;
        synchronized (lock) {
            if (drainWaiters.isEmpty()) {
                return;
            }
            waiters = List.copyOf(drainWaiters);
            drainWaiters.clear();
        }
        for (CompletableFuture<Void> waiter : waiters) {
            waiter.complete(null);
        }
    }

    @Contract(" -> new")
    @NotNull QueueStatistics statistics() {
        synchronized (lock) {
            return new QueueStatistics(capacity, items.size(), droppedCount.get());
        }
    }

    @Contract(mutates = "this")
    @TestOnly
    void setBeforeInvokeDeliverHookForTesting(@NotNull Runnable hook) {
        this.beforeInvokeDeliverHookForTesting = Objects.requireNonNull(hook, "hook");
    }

    private enum QueueState {
        ACCEPTING,
        DRAINING,
        FORCIBLY_STOPPED
    }

    private record QueueItem(@NotNull String text, @NotNull CompletableFuture<Void> future) {

    }

    record SubmitOutcome(@NotNull CompletableFuture<Void> future, boolean shouldActivate) {

    }
}