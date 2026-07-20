package de.silke.dbans.telegram.client;

import de.silke.dbans.telegram.config.QueueOverflowPolicy;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
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
    private boolean accepting = true;

    ChatDeliveryQueue(@NotNull String chatId, int capacity, @NotNull QueueOverflowPolicy overflowPolicy,
                      @NotNull TelegramDeliverySender sender
    ) {
        this.chatId = chatId;
        this.capacity = capacity;
        this.overflowPolicy = overflowPolicy;
        this.sender = sender;
    }

    @Contract(value = " -> new", pure = true)
    private static @NotNull IllegalStateException shutdownException() {
        return new IllegalStateException("TelegramClient is shutdown and no longer accepts messages");
    }

    @NotNull CompletableFuture<Void> submit(@NotNull String text) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Throwable rejection = null;
        boolean startNow = false;

        synchronized (lock) {
            if (!accepting) {
                rejection = shutdownException();
            } else if (items.size() >= capacity) {
                droppedCount.incrementAndGet();
                rejection = overflowFailure();
            } else {
                items.add(new QueueItem(text, future));
                startNow = items.size() == 1;
            }
        }

        if (rejection != null) {
            future.completeExceptionally(rejection);
        } else if (startNow) {
            startNext();
        }
        return future;
    }

    private @NotNull TelegramQueueFullException overflowFailure() {
        return switch (overflowPolicy) {
            case DROP_NEWEST -> new TelegramQueueFullException(chatId, capacity);
        };
    }

    private void startNext() {
        QueueItem head;
        synchronized (lock) {
            head = items.peek();
        }
        if (head != null && !head.future().isDone()) {
            sender.deliver(chatId, head.text())
                  .whenComplete((v, ex) -> onDeliveryComplete(head, ex));
        }
    }

    private void onDeliveryComplete(@NotNull QueueItem item, @Nullable Throwable ex) {
        boolean idle;
        synchronized (lock) {
            items.poll();
            idle = items.isEmpty();
        }
        completeResult(item.future(), ex);

        if (idle) {
            completeDrainWaiters();
        } else {
            startNext();
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
            accepting = false;
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
            remaining = List.copyOf(items);
            items.clear();
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

    private record QueueItem(@NotNull String text, @NotNull CompletableFuture<Void> future) {

    }

}