package com.ecommerce.order.worker;

import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.service.DispatchNudger;
import com.ecommerce.order.store.WorkflowOutboxStore;
import com.ecommerce.order.store.WorkflowOutboxStore.DispatchRow;
import com.ecommerce.order.store.WorkflowOutboxStore.DispatchCompletion;
import com.ecommerce.order.store.WorkflowOutboxStore.MessageRow;
import com.ecommerce.order.store.WorkflowOutboxStore.MessageCompletion;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class WorkflowRollingClaimsTest {
    private final WorkflowOutboxStore store = mock(WorkflowOutboxStore.class);
    private final WorkflowGateway gateway = mock(WorkflowGateway.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Queue<DispatchRow> starts = new ConcurrentLinkedQueue<>();
    private final List<MessageRow> messages = new CopyOnWriteArrayList<>();
    private final Set<UUID> ownedStarts = ConcurrentHashMap.newKeySet();
    private final Set<String> ownedMessages = ConcurrentHashMap.newKeySet();
    private final AtomicInteger maxOwned = new AtomicInteger();
    private final AtomicInteger emptyClaims = new AtomicInteger();
    private final List<MessageCompletion> messageAcks = new CopyOnWriteArrayList<>();
    private WorkflowDispatcher dispatcher;

    @AfterEach void stop() { if (dispatcher != null) dispatcher.stop(); }

    private void setup() {
        setup(Duration.ofSeconds(30));
    }

    private void setup(Duration lease) {
        when(store.claimDispatches(anyString(), anyInt(), any())).thenAnswer(i -> {
            return claimStarts(i.getArgument(1), List.of());
        });
        when(store.claimDispatches(anyString(), anyInt(), any(), anyList())).thenAnswer(i -> claimStarts(i.getArgument(1), i.getArgument(3)));
        when(store.claimMessages(anyString(), anyInt(), any())).thenAnswer(i -> claimMessages(i.getArgument(1), List.of()));
        when(store.claimMessages(anyString(), anyInt(), any(), anyList())).thenAnswer(i -> claimMessages(i.getArgument(1), i.getArgument(3)));
        when(store.completeDispatches(anyString(), anyList())).thenAnswer(i -> {
            List<DispatchCompletion> rows = i.getArgument(1);
            rows.forEach(r -> ownedStarts.remove(r.dispatchId()));
            return rows.stream().map(DispatchCompletion::dispatchId).toList();
        });
        when(store.completeMessages(anyString(), anyList())).thenAnswer(i -> {
            List<MessageCompletion> rows = i.getArgument(1); messageAcks.addAll(rows);
            rows.forEach(r -> ownedMessages.remove(r.messageId()));
            return rows.stream().map(MessageCompletion::messageId).toList();
        });
        var cfg = new OrderProperties.Workers(true, "rolling-test", Duration.ofMillis(200), 50,
                lease, Duration.ofSeconds(30), 8, 50, Duration.ofMillis(20));
        var props = new OrderProperties(null,null,null,null,null,null,null,null,null,cfg,null,null);
        dispatcher = new WorkflowDispatcher(store, new StaticListableBeanFactory(Map.of("gateway", gateway))
                .getBeanProvider(WorkflowGateway.class), mock(ClosedTargetResolver.class), new DispatchNudger(),
                new TransactionTemplate(new NoopTransactions()), new Json(new ObjectMapper()), props, meters);
    }

    private List<DispatchRow> claimStarts(int limit, List<UUID> excluded) {
            var rows = new ArrayList<DispatchRow>();
            for (int n = 0; n < limit; n++) {
                var row = starts.poll(); if (row == null) break;
                assertThat(excluded).doesNotContain(row.dispatchId());
                assertThat(ownedStarts.add(row.dispatchId())).isTrue(); rows.add(row);
            }
            checkBounds(); if (rows.isEmpty()) emptyClaims.incrementAndGet(); return rows;
    }

    private synchronized List<MessageRow> claimMessages(int limit, List<String> excluded) {
        var rows = messages.stream().filter(r -> !excluded.contains(r.targetWorkflowId())).limit(limit).toList();
        rows.forEach(r -> { messages.remove(r); assertThat(ownedMessages.add(r.messageId())).isTrue(); });
        checkBounds(); return rows;
    }

    private void checkBounds() {
        assertThat(ownedStarts.size()).isLessThanOrEqualTo(50);
        assertThat(ownedMessages.size()).isLessThanOrEqualTo(50);
        maxOwned.accumulateAndGet(ownedStarts.size() + ownedMessages.size(), Math::max);
    }

    @Test void laterBatchesProgressBeforeTheSlowFirstBatchRpcFinishesAndClaimsStayBounded() throws Exception {
        setup();
        for (int i = 0; i < 200; i++) starts.add(start("start-" + i));
        CountDownLatch slowStarted = new CountDownLatch(1), release = new CountDownLatch(1), later = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        when(gateway.start(anyString(), anyString(), anyString(), any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                String id = i.getArgument(0);
                if (id.equals("start-0")) { slowStarted.countDown(); release.await(); }
                if (id.equals("start-150")) later.countDown();
                return WorkflowGateway.Result.STARTED;
            } finally { active.decrementAndGet(); }
        });
        dispatcher.start();
        assertThat(slowStarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(later.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(release.getCount()).isEqualTo(1);
        assertThat(peak.get()).isLessThanOrEqualTo(8);
        assertThat(maxOwned.get()).isLessThanOrEqualTo(100);
        release.countDown();
    }

    @Test void newMessagesForAnOwnedLaneWaitWhileAnotherTargetMakesProgress() throws Exception {
        setup(); messages.add(message("a-first", "a", 0));
        CountDownLatch first = new CountDownLatch(1), release = new CountDownLatch(1), other = new CountDownLatch(1), successor = new CountDownLatch(1);
        when(gateway.signalWithStart(anyString(), anyString(), anyString(), any(), any())).thenAnswer(i -> {
            WorkflowMessage msg = i.getArgument(4);
            if (msg.messageId().equals("a-first")) { first.countDown(); release.await(); }
            if (msg.messageId().equals("b")) other.countDown();
            if (msg.messageId().equals("a-next")) successor.countDown();
            return WorkflowGateway.Result.SIGNALLED;
        });
        dispatcher.start(); assertThat(first.await(3, TimeUnit.SECONDS)).isTrue();
        messages.add(message("a-next", "a", 1)); messages.add(message("b", "b", 2));
        assertThat(other.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(successor.getCount()).isEqualTo(1);
        release.countDown(); assertThat(successor.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test void retryBackoffSurvivesAnIdlePassAndDoesNotBlockOtherTargets() throws Exception {
        setup(); messages.add(message("head", "a", 0));
        CountDownLatch retried = new CountDownLatch(1), other = new CountDownLatch(1), successor = new CountDownLatch(1);
        when(gateway.signalWithStart(anyString(), anyString(), anyString(), any(), any())).thenAnswer(i -> {
            WorkflowMessage msg = i.getArgument(4);
            if (msg.messageId().equals("head")) return WorkflowGateway.Result.RETRY;
            if (msg.messageId().equals("b")) other.countDown();
            if (msg.messageId().equals("next")) successor.countDown();
            return WorkflowGateway.Result.SIGNALLED;
        });
        doAnswer(i -> {
            List<MessageCompletion> rows = i.getArgument(1); messageAcks.addAll(rows);
            rows.forEach(r -> ownedMessages.remove(r.messageId())); retried.countDown();
            return rows.stream().map(MessageCompletion::messageId).toList();
        }).when(store).completeMessages(anyString(), anyList());
        dispatcher.start(); assertThat(retried.await(3, TimeUnit.SECONDS)).isTrue();
        messages.add(message("next", "a", 1)); messages.add(message("b", "b", 2));
        assertThat(other.await(700, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(successor.await(100, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(successor.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test void anEmptyQueueIsPolledAtTheConfiguredIntervalWhileARpcIsBlocked() throws Exception {
        setup(); starts.add(start("slow"));
        CountDownLatch began = new CountDownLatch(1), release = new CountDownLatch(1);
        when(gateway.start(anyString(), anyString(), anyString(), any())).thenAnswer(i -> {
            began.countDown(); release.await(); return WorkflowGateway.Result.STARTED;
        });
        dispatcher.start(); assertThat(began.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(release.await(650, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(emptyClaims.get()).isBetween(2, 4);
        dispatcher.stop();
        assertThat(meters.get("order.workflow.dispatch.claimed").gauge().value()).isZero();
    }

    @Test void aRpcThatLostItsLeaseStaysInTheWindowAndExcludedUntilItReturns() throws Exception {
        setup(Duration.ofSeconds(1));
        DispatchRow slow = start("lost-lease"); starts.add(slow);
        CountDownLatch began = new CountDownLatch(1), lost = new CountDownLatch(1), release = new CountDownLatch(1), later = new CountDownLatch(1);
        when(store.renewDispatches(anyString(), anyList(), any())).thenAnswer(i -> { lost.countDown(); return List.of(); });
        when(gateway.start(anyString(), anyString(), anyString(), any())).thenAnswer(i -> {
            if (i.getArgument(0).equals("lost-lease")) { began.countDown(); release.await(); }
            else later.countDown();
            return WorkflowGateway.Result.STARTED;
        });
        dispatcher.start(); assertThat(began.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(lost.await(3, TimeUnit.SECONDS)).isTrue();
        starts.add(start("unrelated")); assertThat(later.await(3, TimeUnit.SECONDS)).isTrue();
        verify(store, atLeastOnce()).claimDispatches(anyString(), anyInt(), any(), argThat(ids -> ids.contains(slow.dispatchId())));
        assertThat(meters.get("order.workflow.dispatch.claimed").gauge().value()).isBetween(1.0, 2.0);
        release.countDown();
    }

    private static DispatchRow start(String name) {
        return new DispatchRow(UUID.randomUUID(), null, name, "OrderCheckoutWorkflow", "queue", "{}", "IN_FLIGHT", 1);
    }
    private static MessageRow message(String id, String target, int ordinal) {
        return new MessageRow(id, 1L, target, "OrderCheckoutWorkflow", "queue", "{}", WorkflowMessage.PAYMENT_RESULT,
                "{}", "IN_FLIGHT", 1, Instant.parse("2026-10-21T00:00:00Z").plusSeconds(ordinal));
    }
    private static class NoopTransactions extends AbstractPlatformTransactionManager {
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object t, TransactionDefinition d) { }
        protected void doCommit(DefaultTransactionStatus s) { }
        protected void doRollback(DefaultTransactionStatus s) { }
    }
}
