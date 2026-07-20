package de.silke.dbans.telegram.client;

import de.silke.dbans.telegram.config.QueueOverflowPolicy;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatDeliveryQueueTest {

    private static final String CHAT_ID = "chat-1";

    private static void awaitQuietly(@NotNull CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static @NotNull ChatDeliveryQueue queue(int capacity, @NotNull ControllableSender sender) {
        return new ChatDeliveryQueue(CHAT_ID, capacity, QueueOverflowPolicy.DROP_NEWEST, sender);
    }

    @Test
    void submit_belowCapacity_isAcceptedAndStartsDelivery() {
        ControllableSender sender = new ControllableSender();
        ChatDeliveryQueue queue = queue(3, sender);

        CompletableFuture<Void> result = queue.submit("a");

        assertThat(result).isNotDone();
        assertThat(sender.deliveredText).containsExactly("a");
        assertThat(queue.statistics()).isEqualTo(new QueueStatistics(3, 1, 0));
    }

    @Test
    void submit_upToCapacityBoundary_isAccepted() {
        ControllableSender sender = new ControllableSender();
        ChatDeliveryQueue queue = queue(2, sender);

        queue.submit("a");
        queue.submit("b");

        assertThat(queue.statistics()).isEqualTo(new QueueStatistics(2, 2, 0));
        assertThat(sender.deliveredText).containsExactly("a");
    }

    @Test
    void submit_beyondCapacity_isRejectedWithDedicatedException() throws Exception {
        ControllableSender sender = new ControllableSender();
        ChatDeliveryQueue queue = queue(2, sender);
        queue.submit("a");
        queue.submit("b");

        CompletableFuture<Void> rejected = queue.submit("c");

        assertThat(rejected).isCompletedExceptionally();
        assertThatThrownBy(rejected::get).cause().isInstanceOf(TelegramQueueFullException.class);
        assertThat(sender.deliveredText).containsExactly("a").doesNotContain("c");
        assertThat(queue.statistics()).isEqualTo(new QueueStatistics(2, 2, 1));
    }

    @Test
    void dropCounter_incrementsExactlyOncePerRejection() {
        ControllableSender sender = new ControllableSender();
        ChatDeliveryQueue queue = queue(1, sender);
        queue.submit("a");

        queue.submit("b");
        queue.submit("c");

        assertThat(queue.statistics().dropped()).isEqualTo(2);
    }

    @Test
    void depth_decreasesAsDeliveriesCompleteAndReachesZero() {
        ControllableSender sender = new ControllableSender();
        ChatDeliveryQueue queue = queue(3, sender);
        queue.submit("a");
        queue.submit("b");
        assertThat(queue.statistics().depth()).isEqualTo(2);

        sender.futureFor(0).complete(null);
        assertThat(queue.statistics().depth()).isEqualTo(1);
        assertThat(sender.deliveredText).containsExactly("a", "b");

        sender.futureFor(1).complete(null);
        assertThat(queue.statistics().depth()).isEqualTo(0);
    }

    @Test
    void fifoOrder_isPreservedAcrossSeveralItems() {
        ControllableSender sender = new ControllableSender();
        ChatDeliveryQueue queue = queue(5, sender);
        queue.submit("a");
        queue.submit("b");
        queue.submit("c");

        sender.futureFor(0).complete(null);
        sender.futureFor(1).complete(null);
        sender.futureFor(2).complete(null);

        assertThat(sender.deliveredText).containsExactly("a", "b", "c");
    }

    @Test
    void failureOfOneItem_doesNotBlockTheNextItem() {
        ControllableSender sender = new ControllableSender();
        ChatDeliveryQueue queue = queue(5, sender);
        CompletableFuture<Void> first = queue.submit("a");
        CompletableFuture<Void> second = queue.submit("b");

        sender.futureFor(0).completeExceptionally(new RuntimeException("boom"));

        assertThat(first).isCompletedExceptionally();
        assertThat(sender.deliveredText).containsExactly("a", "b");
        assertThat(second).isNotDone();

        sender.futureFor(1).complete(null);
        assertThat(second).isCompletedWithValue(null);
    }

    @Test
    void slowOrRetryingDelivery_doesNotConsumeExtraCapacity() {
        ControllableSender sender = new ControllableSender();
        ChatDeliveryQueue queue = queue(1, sender);
        CompletableFuture<Void> first = queue.submit("a");

        assertThat(queue.submit("b")).isCompletedExceptionally();
        assertThat(queue.statistics().dropped()).isEqualTo(1);

        sender.futureFor(0).complete(null);
        assertThat(first).isCompletedWithValue(null);

        CompletableFuture<Void> third = queue.submit("c");
        assertThat(third).isNotDone();
        assertThat(queue.statistics()).isEqualTo(new QueueStatistics(1, 1, 1));
    }

    @Test
    void separateChatQueues_haveIndependentState() {
        ControllableSender sender = new ControllableSender();
        ChatDeliveryQueue chatA = new ChatDeliveryQueue("a", 1, QueueOverflowPolicy.DROP_NEWEST, sender);
        ChatDeliveryQueue chatB = new ChatDeliveryQueue("b", 1, QueueOverflowPolicy.DROP_NEWEST, sender);

        chatA.submit("stuck");
        chatA.submit("dropped");

        assertThat(chatA.statistics().dropped()).isEqualTo(1);
        assertThat(chatB.statistics().dropped()).isEqualTo(0);
        assertThat(chatB.statistics().depth()).isEqualTo(0);
    }

    @Test
    @Timeout(10)
    void concurrentSubmissions_neverExceedCapacity() throws Exception {
        int capacity = 10;
        int producers = 50;
        ControllableSender sender = new ControllableSender();
        ChatDeliveryQueue queue = queue(capacity, sender);

        ExecutorService pool = Executors.newFixedThreadPool(producers);
        CountDownLatch ready = new CountDownLatch(producers);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Void>> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < producers; i++) {
            int index = i;
            pool.submit(() -> {
                ready.countDown();
                awaitQuietly(go);
                results.add(queue.submit("msg-" + index));
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        long accepted = results.stream().filter(f -> !f.isCompletedExceptionally()).count();
        long dropped = results.stream().filter(CompletableFuture::isCompletedExceptionally).count();

        assertThat(accepted).isEqualTo(capacity);
        assertThat(dropped).isEqualTo(producers - capacity);
        assertThat(queue.statistics().depth()).isEqualTo(capacity);
        assertThat(queue.statistics().dropped()).isEqualTo(producers - capacity);
    }

    @Test
    void drain_onEmptyQueue_completesImmediately() {
        ChatDeliveryQueue queue = queue(5, new ControllableSender());

        assertThat(queue.drain()).isCompletedWithValue(null);
    }

    @Test
    void drain_waitsUntilQueueBecomesEmpty() {
        ControllableSender sender = new ControllableSender();
        ChatDeliveryQueue queue = queue(5, sender);
        queue.submit("a");

        CompletableFuture<Void> drain = queue.drain();
        assertThat(drain).isNotDone();

        sender.futureFor(0).complete(null);
        assertThat(drain).isCompletedWithValue(null);
    }

    @Test
    void stopAccepting_rejectsNewSubmissionsWithoutTouchingSenderOrDropCounter() throws Exception {
        ControllableSender sender = new ControllableSender();
        ChatDeliveryQueue queue = queue(5, sender);
        queue.stopAccepting();

        CompletableFuture<Void> result = queue.submit("a");

        assertThat(result).isCompletedExceptionally();
        assertThatThrownBy(result::get).cause().isInstanceOf(IllegalStateException.class);
        assertThat(sender.deliveredText).isEmpty();
        assertThat(queue.statistics().dropped()).isEqualTo(0);
    }

    @Test
    void forceCancel_completesRemainingItemsAndDrainWaiters() throws Exception {
        ControllableSender sender = new ControllableSender();
        ChatDeliveryQueue queue = queue(5, sender);
        CompletableFuture<Void> first = queue.submit("a");
        CompletableFuture<Void> second = queue.submit("b");
        CompletableFuture<Void> drain = queue.drain();

        int cancelled = queue.forceCancel();

        assertThat(cancelled).isEqualTo(2);
        assertThat(first).isCompletedExceptionally();
        assertThat(second).isCompletedExceptionally();
        assertThatThrownBy(first::get).isInstanceOf(CancellationException.class);
        assertThat(drain).isCompletedWithValue(null);
        assertThat(queue.statistics().depth()).isEqualTo(0);
    }

    private static final class ControllableSender implements TelegramDeliverySender {

        private final List<String> deliveredText = Collections.synchronizedList(new ArrayList<>());
        private final List<CompletableFuture<Void>> pending = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger cancelCalls = new AtomicInteger();

        @Override
        public @NotNull CompletableFuture<Void> deliver(@NotNull String chatId, @NotNull String text) {
            deliveredText.add(text);
            CompletableFuture<Void> future = new CompletableFuture<>();
            pending.add(future);
            return future;
        }

        @Override
        public void cancelAllPending() {
            cancelCalls.incrementAndGet();
        }

        @NotNull CompletableFuture<Void> futureFor(int index) {
            return pending.get(index);
        }

    }
}