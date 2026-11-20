package com.ecommerce.order.worker;

import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.service.DispatchNudger;
import com.ecommerce.order.store.WorkflowOutboxStore;
import com.ecommerce.order.store.WorkflowOutboxStore.DispatchCompletion;
import com.ecommerce.order.store.WorkflowOutboxStore.DispatchRow;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class WorkflowDispatcherTest {
    private final WorkflowOutboxStore store = mock(WorkflowOutboxStore.class);
    private final WorkflowGateway gateway = mock(WorkflowGateway.class);
    private final ConcurrentLinkedQueue<List<DispatchCompletion>> batches = new ConcurrentLinkedQueue<>();
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor();
    private WorkflowDispatcher dispatcher;

    @AfterEach void stop() {
        coordinator.shutdownNow();
        if (dispatcher != null) dispatcher.stop();
    }

    @Test void eightIndependentCallsRunTogetherAndShareAcknowledgementTransactions() throws Exception {
        List<DispatchRow> rows = rows(16);
        setup(rows, Duration.ofSeconds(30), Duration.ofSeconds(1));
        CountDownLatch eightStarted = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean transactionLeaked = new AtomicBoolean();
        when(gateway.start(anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet(); peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            transactionLeaked.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
            eightStarted.countDown();
            try { release.await(); return WorkflowGateway.Result.STARTED; }
            finally { active.decrementAndGet(); }
        });
        var pass = coordinator.submit(dispatcher::dispatchOnce);
        assertThat(eightStarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(calls.get()).isEqualTo(8);
        assertThat(peak.get()).isEqualTo(8);
        release.countDown();
        assertThat(pass.get(3, TimeUnit.SECONDS)).isEqualTo(16);
        assertThat(transactionLeaked.get()).isFalse();
        assertThat(batches.stream().flatMap(List::stream).map(DispatchCompletion::dispatchId))
                .containsExactlyInAnyOrderElementsOf(rows.stream().map(DispatchRow::dispatchId).toList());
        assertThat(batches).anyMatch(batch -> batch.size() > 1);
        assertThat(batches.size()).isLessThan(16);
    }

    @Test void completedStartsAreFlushedWhileOneRpcIsStillBlocked() throws Exception {
        List<DispatchRow> rows = rows(8);
        setup(rows, Duration.ofSeconds(30), Duration.ofMillis(20));
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch successfulAck = new CountDownLatch(1);
        when(store.completeDispatches(anyString(), anyList())).thenAnswer(invocation -> {
            List<DispatchCompletion> batch = List.copyOf(invocation.getArgument(1));
            batches.add(batch); successfulAck.countDown();
            return batch.stream().map(DispatchCompletion::dispatchId).toList();
        });
        when(gateway.start(anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0).equals(rows.getFirst().workflowId())) releaseSlow.await();
            return WorkflowGateway.Result.STARTED;
        });
        var pass = coordinator.submit(dispatcher::dispatchOnce);
        assertThat(successfulAck.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(pass.isDone()).isFalse();
        assertThat(batches.stream().flatMap(List::stream).map(DispatchCompletion::dispatchId))
                .doesNotContain(rows.getFirst().dispatchId());
        releaseSlow.countDown();
        assertThat(pass.get(3, TimeUnit.SECONDS)).isEqualTo(8);
        assertThat(batches.stream().mapToInt(List::size).sum()).isEqualTo(8);
    }

    @Test void rpcExceptionAndRetryDoNotDiscardTheOtherSuccessfulStarts() throws Exception {
        List<DispatchRow> rows = rows(8);
        setup(rows, Duration.ofSeconds(30), Duration.ofMillis(20));
        when(gateway.start(anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            if (id.equals(rows.get(0).workflowId())) throw new IllegalStateException("transport failed");
            return id.equals(rows.get(1).workflowId()) ? WorkflowGateway.Result.RETRY : WorkflowGateway.Result.STARTED;
        });
        assertThat(dispatcher.dispatchOnce()).isEqualTo(8);
        List<DispatchCompletion> results = batches.stream().flatMap(List::stream).toList();
        assertThat(results).filteredOn(DispatchCompletion::started).hasSize(6);
        assertThat(results).filteredOn(c -> !c.started()).allSatisfy(c -> {
            assertThat(c.attempts()).isEqualTo(1);
            assertThat(c.backoff()).isEqualTo(Duration.ofSeconds(1));
            assertThat(c.error()).isNotBlank();
        });
    }

    @Test void leasesAreRenewedWhileAnRpcHasNotReturned() throws Exception {
        List<DispatchRow> rows = rows(1);
        setup(rows, Duration.ofSeconds(1), Duration.ofMillis(20));
        CountDownLatch renewed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(store.renewDispatches(anyString(), anyList(), any())).thenAnswer(invocation -> {
            renewed.countDown(); return rows.stream().map(DispatchRow::dispatchId).toList();
        });
        when(gateway.start(anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            release.await(); return WorkflowGateway.Result.STARTED;
        });
        var pass = coordinator.submit(dispatcher::dispatchOnce);
        assertThat(renewed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(pass.isDone()).isFalse();
        release.countDown();
        assertThat(pass.get(3, TimeUnit.SECONDS)).isEqualTo(1);
    }

    @Test void stoppingInterruptsRpcWorkersAndStartIsIdempotent() throws Exception {
        setup(rows(8), Duration.ofSeconds(30), Duration.ofMillis(20));
        CountDownLatch started = new CountDownLatch(8);
        var workers = java.util.concurrent.ConcurrentHashMap.<Thread>newKeySet();
        when(gateway.start(anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            workers.add(Thread.currentThread()); started.countDown();
            new CountDownLatch(1).await(); return WorkflowGateway.Result.STARTED;
        });
        dispatcher.start(); dispatcher.start();
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        dispatcher.stop();
        assertThat(dispatcher.isRunning()).isFalse();
        // The pool is terminated when stop() returns; a worker thread object can outlive its last task briefly.
        for (Thread worker : workers) worker.join(1000);
        assertThat(workers).hasSize(8).noneMatch(Thread::isAlive);
    }

    private void setup(List<DispatchRow> rows, Duration lease, Duration flush) {
        when(store.claimDispatches(anyString(), anyInt(), any())).thenReturn(rows, List.of());
        when(store.claimMessages(anyString(), anyInt(), any())).thenReturn(List.of());
        when(store.completeDispatches(anyString(), anyList())).thenAnswer(invocation -> {
            List<DispatchCompletion> batch = List.copyOf(invocation.getArgument(1)); batches.add(batch);
            return batch.stream().map(DispatchCompletion::dispatchId).toList();
        });
        var workers = new OrderProperties.Workers(true, "unit", Duration.ofMillis(200), 50,
                lease, Duration.ofSeconds(30), 8, 50, flush);
        var props = new OrderProperties(null, null, null, null, null, null, null, null, null, workers, null, null);
        var beans = new StaticListableBeanFactory(Map.of("gateway", gateway));
        dispatcher = new WorkflowDispatcher(store, beans.getBeanProvider(WorkflowGateway.class),
                mock(ClosedTargetResolver.class), new DispatchNudger(), new TransactionTemplate(new NoopTransactions()),
                new Json(new ObjectMapper()), props, new SimpleMeterRegistry());
    }

    private static List<DispatchRow> rows(int count) {
        var rows = new ArrayList<DispatchRow>();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            rows.add(new DispatchRow(id, null, "order-checkout-" + id, "OrderCheckoutWorkflow", "queue",
                    "{}", "IN_FLIGHT", 1));
        }
        return rows;
    }

    private static class NoopTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
