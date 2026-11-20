package com.ecommerce.order.worker;

import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.service.DispatchNudger;
import com.ecommerce.order.store.WorkflowOutboxStore;
import com.ecommerce.order.store.WorkflowOutboxStore.DispatchCompletion;
import com.ecommerce.order.store.WorkflowOutboxStore.DispatchRow;
import com.ecommerce.order.store.WorkflowOutboxStore.MessageCompletion;
import com.ecommerce.order.store.WorkflowOutboxStore.MessageRow;
import com.ecommerce.order.workflow.WorkflowMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** ADR-029: messages on the shared bounded RPC pool, one in flight per target, fenced batch ACK. */
@Timeout(10)
class WorkflowMessageLanesTest {
    private static final Instant T0 = Instant.parse("2026-10-20T00:00:00Z");
    private final WorkflowOutboxStore store = mock(WorkflowOutboxStore.class);
    private final WorkflowGateway gateway = mock(WorkflowGateway.class);
    private final ClosedTargetResolver resolver = mock(ClosedTargetResolver.class);
    private final CountingTransactions transactions = new CountingTransactions();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ConcurrentLinkedQueue<List<MessageCompletion>> messageAcks = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Integer> messageAckTransactions = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Integer> startAckTransactions = new ConcurrentLinkedQueue<>();
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-coordinator"));
    private WorkflowDispatcher dispatcher;

    @AfterEach void stop() {
        coordinator.shutdownNow();
        if (dispatcher != null) dispatcher.stop();
    }

    @Test void oneTargetHasAtMostOneMessageInFlightAndKeepsCreatedOrder() throws Exception {
        List<MessageRow> rows = new ArrayList<>();
        rows.addAll(messages("target-a", 4, 0));
        rows.addAll(messages("target-b", 3, 1));
        rows.addAll(messages("target-c", 2, 2));
        List<MessageRow> claimed = new ArrayList<>(rows);
        Collections.shuffle(claimed, new java.util.Random(7)); // RETURNING order is not created_at order
        setup(List.of(), claimed, Duration.ofSeconds(30), Duration.ofMillis(20));
        Map<String, AtomicInteger> perTarget = new ConcurrentHashMap<>();
        Map<String, List<String>> sent = new ConcurrentHashMap<>();
        AtomicInteger overlap = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        when(gateway.signalWithStart(anyString(), anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            String target = invocation.getArgument(0);
            WorkflowMessage message = invocation.getArgument(4);
            if (perTarget.computeIfAbsent(target, t -> new AtomicInteger()).incrementAndGet() > 1) overlap.incrementAndGet();
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            sent.computeIfAbsent(target, t -> new CopyOnWriteArrayList<>()).add(message.messageId());
            try { Thread.sleep(30); return WorkflowGateway.Result.SIGNALLED; }
            finally { active.decrementAndGet(); perTarget.get(target).decrementAndGet(); }
        });
        assertThat(dispatcher.dispatchOnce()).isEqualTo(9);
        assertThat(overlap.get()).isZero();
        assertThat(peak.get()).isEqualTo(3); // three different targets do run together
        for (String target : List.of("target-a", "target-b", "target-c")) {
            assertThat(sent.get(target)).containsExactlyElementsOf(rows.stream()
                    .filter(r -> r.targetWorkflowId().equals(target)).map(MessageRow::messageId).toList());
        }
        assertThat(messageAcks.stream().flatMap(List::stream)).hasSize(9)
                .allSatisfy(c -> assertThat(c.status()).isEqualTo("DELIVERED"));
    }

    @Test void startsAndMessagesShareTheEightRpcSlotsWithoutTransactions() throws Exception {
        List<DispatchRow> starts = starts(16);
        List<MessageRow> messages = new ArrayList<>();
        for (int i = 0; i < 16; i++) messages.addAll(messages("target-" + i, 1, i));
        setup(starts, messages, Duration.ofSeconds(30), Duration.ofSeconds(1));
        CountDownLatch eight = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger startCalls = new AtomicInteger();
        AtomicInteger messageCalls = new AtomicInteger();
        AtomicBoolean transactionLeaked = new AtomicBoolean();
        var threads = ConcurrentHashMap.<String>newKeySet();
        when(gateway.start(anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            startCalls.incrementAndGet();
            return blocked(active, peak, transactionLeaked, threads, eight, release, WorkflowGateway.Result.STARTED);
        });
        when(gateway.signalWithStart(anyString(), anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            messageCalls.incrementAndGet();
            return blocked(active, peak, transactionLeaked, threads, eight, release, WorkflowGateway.Result.SIGNALLED);
        });
        var pass = coordinator.submit(dispatcher::dispatchOnce);
        assertThat(eight.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);
        assertThat(startCalls.get() + messageCalls.get()).isEqualTo(8);
        // Alternating queues: messages are not held behind the claimed start group, nor the reverse.
        assertThat(startCalls.get()).isEqualTo(4);
        assertThat(messageCalls.get()).isEqualTo(4);
        release.countDown();
        assertThat(pass.get(3, TimeUnit.SECONDS)).isEqualTo(32);
        assertThat(peak.get()).isEqualTo(8);
        assertThat(transactionLeaked.get()).isFalse();
        assertThat(threads).allMatch(name -> name.startsWith("order-workflow-rpc-"));
        assertThat(messageAcks.stream().flatMap(List::stream).map(MessageCompletion::messageId))
                .containsExactlyInAnyOrderElementsOf(messages.stream().map(MessageRow::messageId).toList());
    }

    @Test void messageAcknowledgementsAreBatchedFencedAndShareTheStartTransaction() throws Exception {
        List<DispatchRow> starts = starts(6);
        List<MessageRow> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) messages.addAll(messages("target-" + i, 1, i));
        setup(starts, messages, Duration.ofSeconds(30), Duration.ofSeconds(1));
        String stale = messages.get(3).messageId();
        doAnswer(invocation -> {
            List<MessageCompletion> batch = List.copyOf(invocation.getArgument(1));
            messageAcks.add(batch); messageAckTransactions.add(transactions.current());
            // The store returns only IDs whose IN_FLIGHT + owner + attempts still match.
            return batch.stream().map(MessageCompletion::messageId).filter(id -> !id.equals(stale)).toList();
        }).when(store).completeMessages(anyString(), anyList());
        when(gateway.start(anyString(), anyString(), anyString(), any())).thenReturn(WorkflowGateway.Result.STARTED);
        when(gateway.signalWithStart(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(WorkflowGateway.Result.SIGNALLED);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(16);
        verify(store).completeMessages(eq("unit"), anyList());
        assertThat(messageAcks).singleElement().satisfies(batch -> {
            assertThat(batch).hasSize(10).allSatisfy(c -> assertThat(c.attempts()).isEqualTo(2));
        });
        // One ACK transaction carried both statements.
        assertThat(messageAckTransactions).containsExactlyElementsOf(startAckTransactions);
        assertThat(meters.counter("order.workflow.message.ack.stale").count()).isEqualTo(1);
        assertThat(meters.summary("order.workflow.message.ack.batch").totalAmount()).isEqualTo(10);
    }

    @Test void aRetriedHeadDefersItsLaneWithoutSendingTheSuccessors() throws Exception {
        List<MessageRow> lane = messages("target-a", 3, 0);
        List<MessageRow> other = messages("target-b", 2, 1);
        List<MessageRow> all = new ArrayList<>(lane); all.addAll(other);
        setup(List.of(), all, Duration.ofSeconds(30), Duration.ofMillis(20));
        var sent = new CopyOnWriteArrayList<String>();
        when(gateway.signalWithStart(anyString(), anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            WorkflowMessage message = invocation.getArgument(4);
            sent.add(message.messageId());
            return message.messageId().equals(lane.getFirst().messageId()) ? WorkflowGateway.Result.RETRY
                    : WorkflowGateway.Result.SIGNALLED;
        });
        assertThat(dispatcher.dispatchOnce()).isEqualTo(5);
        assertThat(sent).containsExactlyInAnyOrder(lane.getFirst().messageId(), other.get(0).messageId(),
                other.get(1).messageId());
        Map<String, MessageCompletion> results = completions();
        MessageCompletion head = results.get(lane.getFirst().messageId());
        assertThat(head.status()).isEqualTo("RETRY");
        assertThat(head.backoff()).isEqualTo(Duration.ofSeconds(2));
        for (MessageRow successor : lane.subList(1, 3)) {
            MessageCompletion deferred = results.get(successor.messageId());
            assertThat(deferred.status()).isEqualTo("RETRY");
            assertThat(deferred.backoff()).isEqualTo(head.backoff());
            assertThat(deferred.error()).contains("Deferred behind message " + lane.getFirst().messageId());
        }
        assertThat(other).allSatisfy(r -> assertThat(results.get(r.messageId()).status()).isEqualTo("DELIVERED"));
    }

    @Test void closedTargetResolutionRunsOnTheCoordinatorAndTheLaneContinues() throws Exception {
        List<MessageRow> lane = messages("target-a", 2, 0);
        setup(List.of(), lane, Duration.ofSeconds(30), Duration.ofMillis(20));
        AtomicReference<String> resolverThread = new AtomicReference<>();
        when(gateway.signalWithStart(anyString(), anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            WorkflowMessage message = invocation.getArgument(4);
            return message.messageId().equals(lane.getFirst().messageId()) ? WorkflowGateway.Result.CLOSED_TARGET
                    : WorkflowGateway.Result.SIGNALLED;
        });
        when(resolver.resolve(any())).thenAnswer(invocation -> {
            resolverThread.set(Thread.currentThread().getName());
            return new ClosedTargetResolver.Resolution("REROUTED", "replacement");
        });
        assertThat(coordinator.submit(dispatcher::dispatchOnce).get(3, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(resolverThread.get()).isEqualTo("test-coordinator");
        Map<String, MessageCompletion> results = completions();
        assertThat(results.get(lane.get(0).messageId())).satisfies(c -> {
            assertThat(c.status()).isEqualTo("DELIVERED");
            assertThat(c.outcome()).isEqualTo("REROUTED");
            assertThat(c.replacementId()).isEqualTo("replacement");
        });
        assertThat(results.get(lane.get(1).messageId()).outcome()).isEqualTo("SIGNALLED");
    }

    @Test void ambiguousOrFailedResolutionQuarantinesOrRetries() throws Exception {
        List<MessageRow> rows = new ArrayList<>(messages("target-a", 1, 0));
        rows.addAll(messages("target-b", 1, 1));
        setup(List.of(), rows, Duration.ofSeconds(30), Duration.ofMillis(20));
        when(gateway.signalWithStart(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(WorkflowGateway.Result.CLOSED_TARGET);
        when(resolver.resolve(any())).thenAnswer(invocation -> {
            MessageRow row = invocation.getArgument(0);
            if (row.targetWorkflowId().equals("target-a")) return null;
            throw new IllegalStateException("db down");
        });
        dispatcher.dispatchOnce();
        Map<String, MessageCompletion> results = completions();
        assertThat(results.get(rows.get(0).messageId()).status()).isEqualTo("QUARANTINED");
        assertThat(results.get(rows.get(1).messageId())).satisfies(c -> {
            assertThat(c.status()).isEqualTo("RETRY");
            assertThat(c.error()).contains("db down");
        });
    }

    @Test void messageLeasesAreRenewedAndRowsOwnedElsewhereAreNotSent() throws Exception {
        List<MessageRow> lane = messages("target-a", 2, 0);
        setup(List.of(), lane, Duration.ofSeconds(1), Duration.ofMillis(20));
        CountDownLatch renewed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var renewedRows = new CopyOnWriteArrayList<String>();
        when(store.renewMessages(anyString(), anyList(), any())).thenAnswer(invocation -> {
            List<MessageRow> rows = invocation.getArgument(1);
            rows.forEach(r -> renewedRows.add(r.messageId()));
            renewed.countDown();
            return List.of(lane.getFirst().messageId()); // the successor now belongs to another claim generation
        });
        var sent = new CopyOnWriteArrayList<String>();
        when(gateway.signalWithStart(anyString(), anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            WorkflowMessage message = invocation.getArgument(4);
            sent.add(message.messageId());
            release.await();
            return WorkflowGateway.Result.SIGNALLED;
        });
        var pass = coordinator.submit(dispatcher::dispatchOnce);
        assertThat(renewed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(pass.isDone()).isFalse();
        assertThat(renewedRows).contains(lane.get(0).messageId(), lane.get(1).messageId());
        release.countDown();
        assertThat(pass.get(3, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(sent).containsExactly(lane.getFirst().messageId());
        assertThat(completions()).containsOnlyKeys(lane.getFirst().messageId());
    }

    @Test void stoppingInterruptsMessageWorkersAndLeavesUnknownResultsLeased() throws Exception {
        List<MessageRow> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) rows.addAll(messages("target-" + i, 1, i));
        setup(List.of(), rows, Duration.ofSeconds(30), Duration.ofMillis(20));
        CountDownLatch started = new CountDownLatch(8);
        var workers = ConcurrentHashMap.<Thread>newKeySet();
        when(gateway.signalWithStart(anyString(), anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            workers.add(Thread.currentThread()); started.countDown();
            new CountDownLatch(1).await(); return WorkflowGateway.Result.SIGNALLED;
        });
        dispatcher.start(); dispatcher.start();
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        dispatcher.stop();
        assertThat(dispatcher.isRunning()).isFalse();
        // The pool is terminated when stop() returns; a worker thread object can outlive its last task briefly.
        for (Thread worker : workers) worker.join(1000);
        assertThat(workers).hasSize(8).noneMatch(Thread::isAlive)
                .allMatch(t -> t.getName().startsWith("order-workflow-rpc-"));
        verify(store, never()).completeMessages(anyString(), anyList());
    }

    private WorkflowGateway.Result blocked(AtomicInteger active, AtomicInteger peak, AtomicBoolean transactionLeaked,
                                           java.util.Set<String> threads, CountDownLatch started,
                                           CountDownLatch release, WorkflowGateway.Result result) throws Exception {
        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
        transactionLeaked.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
        threads.add(Thread.currentThread().getName());
        started.countDown();
        try { release.await(); return result; }
        finally { active.decrementAndGet(); }
    }

    private Map<String, MessageCompletion> completions() {
        Map<String, MessageCompletion> results = new java.util.HashMap<>();
        messageAcks.stream().flatMap(List::stream).forEach(c -> assertThat(results.put(c.messageId(), c)).isNull());
        return results;
    }

    private void setup(List<DispatchRow> starts, List<MessageRow> messages, Duration lease, Duration flush) {
        when(store.claimDispatches(anyString(), anyInt(), any())).thenReturn(starts, List.of());
        when(store.claimMessages(anyString(), anyInt(), any())).thenReturn(messages, List.of());
        when(store.completeDispatches(anyString(), anyList())).thenAnswer(invocation -> {
            List<DispatchCompletion> batch = List.copyOf(invocation.getArgument(1));
            startAckTransactions.add(transactions.current());
            return batch.stream().map(DispatchCompletion::dispatchId).toList();
        });
        when(store.completeMessages(anyString(), anyList())).thenAnswer(invocation -> {
            List<MessageCompletion> batch = List.copyOf(invocation.getArgument(1));
            messageAcks.add(batch); messageAckTransactions.add(transactions.current());
            return batch.stream().map(MessageCompletion::messageId).toList();
        });
        var workers = new OrderProperties.Workers(true, "unit", Duration.ofMillis(200), 50,
                lease, Duration.ofSeconds(30), 8, 50, flush);
        var props = new OrderProperties(null, null, null, null, null, null, null, null, null, workers, null, null);
        var beans = new StaticListableBeanFactory(Map.of("gateway", gateway));
        dispatcher = new WorkflowDispatcher(store, beans.getBeanProvider(WorkflowGateway.class), resolver,
                new DispatchNudger(), new TransactionTemplate(transactions), new Json(new ObjectMapper()), props, meters);
    }

    private static List<DispatchRow> starts(int count) {
        var rows = new ArrayList<DispatchRow>();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            rows.add(new DispatchRow(id, null, "order-checkout-" + id, "OrderCheckoutWorkflow", "queue", "{}",
                    "IN_FLIGHT", 1));
        }
        return rows;
    }

    /** Messages for one target, created one second apart; offset staggers targets. */
    private static List<MessageRow> messages(String target, int count, int offset) {
        var rows = new ArrayList<MessageRow>();
        for (int i = 0; i < count; i++) {
            rows.add(new MessageRow(target + "-m" + i + "-" + UUID.randomUUID(), 1L, target, "OrderCheckoutWorkflow",
                    "queue", "{}", WorkflowMessage.PAYMENT_RESULT, "{}", "IN_FLIGHT", 2,
                    T0.plusSeconds(i * 10L + offset)));
        }
        return rows;
    }

    /** Numbers transactions so a test can tell which statements committed together. */
    private static class CountingTransactions extends AbstractPlatformTransactionManager {
        private final AtomicInteger sequence = new AtomicInteger();
        private final ThreadLocal<Integer> current = new ThreadLocal<>();
        int current() { return current.get(); }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            current.set(sequence.incrementAndGet());
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { current.remove(); }
        @Override protected void doRollback(DefaultTransactionStatus status) { current.remove(); }
    }
}
