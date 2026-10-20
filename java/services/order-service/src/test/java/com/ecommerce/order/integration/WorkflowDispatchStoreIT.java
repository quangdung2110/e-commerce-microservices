package com.ecommerce.order.integration;

import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.service.DispatchNudger;
import com.ecommerce.order.store.WorkflowOutboxStore;
import com.ecommerce.order.store.WorkflowOutboxStore.DispatchCompletion;
import com.ecommerce.order.store.WorkflowOutboxStore.DispatchRow;
import com.ecommerce.order.store.WorkflowOutboxStore.MessageCompletion;
import com.ecommerce.order.store.WorkflowOutboxStore.MessageRow;
import com.ecommerce.order.worker.ClosedTargetResolver;
import com.ecommerce.order.worker.WorkflowDispatcher;
import com.ecommerce.order.worker.WorkflowGateway;
import com.ecommerce.order.workflow.WorkflowInput;
import com.ecommerce.order.workflow.WorkflowMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Real PostgreSQL coverage for batched ACK, claim generations, rollback and SKIP LOCKED (ADR-028/029). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(15)
class WorkflowDispatchStoreIT {
    private PostgreSQLContainer<?> postgres;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private WorkflowOutboxStore store;
    private TransactionTemplate tx;
    private String schema;

    @BeforeAll void database() {
        String url = System.getenv("TEST_DB_URL");
        if (url == null) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine"); postgres.start(); url = postgres.getJdbcUrl();
        }
        String user = postgres == null ? System.getenv().getOrDefault("DB_USERNAME", "postgres") : postgres.getUsername();
        String password = postgres == null ? System.getenv().getOrDefault("DB_PASSWORD", "postgres") : postgres.getPassword();
        schema = "dispatch_test_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        admin.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public");
        admin.execute("CREATE SCHEMA " + schema);
        Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema).load().migrate();
        var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema + ",public", user, password);
        jdbc = new JdbcTemplate(ds); jdbc.setQueryTimeout(3);
        store = new WorkflowOutboxStore(new NamedParameterJdbcTemplate(jdbc));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @BeforeEach void reset() { jdbc.execute("TRUNCATE order_workflow_dispatches, order_workflow_messages"); }
    @AfterAll void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
        if (postgres != null) postgres.stop();
    }

    @Test void mixedSuccessAndRetryAreAppliedInOneBatch() {
        insert(2);
        List<DispatchRow> claimed = claim("A", 2);
        DispatchRow ok = claimed.get(0), retry = claimed.get(1);
        var applied = tx.execute(s -> store.completeDispatches("A", List.of(DispatchCompletion.started(ok),
                DispatchCompletion.retry(retry, "unavailable", Duration.ofSeconds(10)))));
        assertThat(applied).containsExactlyInAnyOrder(ok.dispatchId(), retry.dispatchId());
        assertThat(status(ok)).isEqualTo("STARTED"); assertThat(status(retry)).isEqualTo("RETRY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_workflow_dispatches WHERE lease_owner IS NOT NULL OR lease_until IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT available_at > NOW() + INTERVAL '8 seconds' FROM order_workflow_dispatches WHERE dispatch_id=?", Boolean.class, retry.dispatchId())).isTrue();
        assertThat(claim("B", 2)).isEmpty();
    }

    @Test void staleOwnerCannotAckRetryOrRenewANewerClaim() {
        assertStaleClaimRejected("A", "B");
    }

    @Test void samePodReclaimStillRejectsItsPreviousAttempt() {
        assertStaleClaimRejected("same-pod", "same-pod");
    }

    private void assertStaleClaimRejected(String firstOwner, String secondOwner) {
        insert(1);
        DispatchRow old = claim(firstOwner, 1).getFirst();
        expire();
        DispatchRow current = claim(secondOwner, 1).getFirst();
        assertThat(current.attempts()).isEqualTo(old.attempts() + 1);
        assertThat(tx.<List<UUID>>execute(s -> store.completeDispatches(firstOwner, List.of(DispatchCompletion.started(old))))).isEmpty();
        assertThat(tx.<List<UUID>>execute(s -> store.renewDispatches(firstOwner, List.of(old), Duration.ofSeconds(30)))).isEmpty();
        assertThat(status(current)).isEqualTo("IN_FLIGHT");
        tx.executeWithoutResult(s -> store.completeDispatches(secondOwner, List.of(DispatchCompletion.started(current))));
        assertThat(tx.<List<UUID>>execute(s -> store.completeDispatches(firstOwner, List.of(
                DispatchCompletion.retry(old, "late failure", Duration.ofSeconds(1)))))).isEmpty();
        assertThat(status(current)).isEqualTo("STARTED");
    }

    @Test void rollbackOfBatchAckRetainsWorkForCrashRecovery() {
        insert(2); List<DispatchRow> claimed = claim("A", 2);
        tx.executeWithoutResult(s -> {
            store.completeDispatches("A", claimed.stream().map(DispatchCompletion::started).toList());
            s.setRollbackOnly();
        });
        assertThat(claimed).allSatisfy(r -> assertThat(status(r)).isEqualTo("IN_FLIGHT"));
        expire();
        List<DispatchRow> recovered = claim("B", 2);
        assertThat(recovered).hasSize(2).allMatch(r -> r.attempts() == 2);
        tx.executeWithoutResult(s -> store.completeDispatches("B", recovered.stream().map(DispatchCompletion::started).toList()));
        assertThat(recovered).allSatisfy(r -> assertThat(status(r)).isEqualTo("STARTED"));
    }

    @Test void renewalExtendsOnlyTheCurrentInflightGeneration() {
        insert(1); DispatchRow row = claim("A", 1).getFirst();
        OffsetDateTime before = jdbc.queryForObject("SELECT lease_until FROM order_workflow_dispatches", OffsetDateTime.class);
        assertThat(tx.<List<UUID>>execute(s -> store.renewDispatches("A", List.of(row), Duration.ofSeconds(60))))
                .containsExactly(row.dispatchId());
        OffsetDateTime after = jdbc.queryForObject("SELECT lease_until FROM order_workflow_dispatches", OffsetDateTime.class);
        assertThat(after).isAfter(before.plusSeconds(20));
        tx.executeWithoutResult(s -> store.completeDispatches("A", List.of(DispatchCompletion.started(row))));
        assertThat(tx.<List<UUID>>execute(s -> store.renewDispatches("A", List.of(row), Duration.ofSeconds(60)))).isEmpty();
    }

    @Test void anotherConsumerSkipsRowsHeldByAnUncommittedClaim() throws Exception {
        insert(4);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var consumer = Executors.newSingleThreadExecutor()) {
            var first = consumer.submit(() -> tx.execute(s -> {
                List<DispatchRow> rows = store.claimDispatches("A", 2, Duration.ofSeconds(30));
                locked.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return rows;
            }));
            try {
                assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
                List<DispatchRow> second = claim("B", 2);
                assertThat(second).hasSize(2);
                release.countDown();
                assertThat(first.get(3, TimeUnit.SECONDS).stream().map(DispatchRow::dispatchId))
                        .doesNotContainAnyElementsOf(second.stream().map(DispatchRow::dispatchId).toList());
            } finally { release.countDown(); }
        }
    }

    // ---- ADR-029: messages ----

    @Test void deliveredRetriedAndQuarantinedMessagesAreAppliedInOneFencedBatch() {
        insertMessages("target-a", 3);
        List<MessageRow> claimed = claimMessages("A", 3);
        MessageRow ok = claimed.get(0), retry = claimed.get(1), quarantined = claimed.get(2);
        var applied = tx.execute(s -> store.completeMessages("A", List.of(
                MessageCompletion.delivered(ok, "REROUTED", "replacement-1"),
                MessageCompletion.retry(retry, "unavailable", Duration.ofSeconds(10)),
                MessageCompletion.quarantined(quarantined, "ambiguous"))));
        assertThat(applied).containsExactlyInAnyOrder(ok.messageId(), retry.messageId(), quarantined.messageId());
        assertThat(jdbc.queryForMap("SELECT status, delivery_outcome, replacement_message_id, last_error, delivered_at IS NOT NULL AS delivered FROM order_workflow_messages WHERE message_id=?", ok.messageId()))
                .containsEntry("status", "DELIVERED").containsEntry("delivery_outcome", "REROUTED")
                .containsEntry("replacement_message_id", "replacement-1").containsEntry("delivered", true)
                .containsEntry("last_error", null);
        assertThat(jdbc.queryForMap("SELECT status, last_error, delivery_outcome, delivered_at IS NULL AS undelivered, available_at > NOW() + INTERVAL '8 seconds' AS later FROM order_workflow_messages WHERE message_id=?", retry.messageId()))
                .containsEntry("status", "RETRY").containsEntry("last_error", "unavailable")
                .containsEntry("delivery_outcome", null).containsEntry("undelivered", true).containsEntry("later", true);
        assertThat(messageStatus(quarantined.messageId())).isEqualTo("QUARANTINED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_workflow_messages WHERE lease_owner IS NOT NULL OR lease_until IS NOT NULL", Integer.class)).isZero();
        assertThat(claimMessages("B", 3)).isEmpty();
    }

    @Test void staleMessageOwnerCannotAckOrRenewANewerClaim() {
        assertStaleMessageClaimRejected("A", "B");
    }

    @Test void samePodMessageReclaimStillRejectsItsPreviousAttempt() {
        assertStaleMessageClaimRejected("same-pod", "same-pod");
    }

    private void assertStaleMessageClaimRejected(String firstOwner, String secondOwner) {
        insertMessages("target-a", 1);
        MessageRow old = claimMessages(firstOwner, 1).getFirst();
        expireMessages();
        MessageRow current = claimMessages(secondOwner, 1).getFirst();
        assertThat(current.attempts()).isEqualTo(old.attempts() + 1);
        assertThat(tx.<List<String>>execute(s -> store.completeMessages(firstOwner,
                List.of(MessageCompletion.delivered(old, "SIGNALLED", null))))).isEmpty();
        assertThat(tx.<List<String>>execute(s -> store.renewMessages(firstOwner, List.of(old), Duration.ofSeconds(30)))).isEmpty();
        assertThat(messageStatus(current.messageId())).isEqualTo("IN_FLIGHT");
        tx.executeWithoutResult(s -> store.completeMessages(secondOwner, List.of(MessageCompletion.delivered(current, "SIGNALLED", null))));
        assertThat(tx.<List<String>>execute(s -> store.completeMessages(firstOwner, List.of(
                MessageCompletion.retry(old, "late failure", Duration.ofSeconds(1)))))).isEmpty();
        assertThat(tx.<List<String>>execute(s -> store.completeMessages(firstOwner, List.of(
                MessageCompletion.quarantined(old, "late quarantine"))))).isEmpty();
        assertThat(messageStatus(current.messageId())).isEqualTo("DELIVERED");
    }

    @Test void rollbackOfMessageBatchRetainsWorkForReclaim() {
        insertMessages("target-a", 2);
        List<MessageRow> claimed = claimMessages("A", 2);
        tx.executeWithoutResult(s -> {
            store.completeMessages("A", claimed.stream().map(r -> MessageCompletion.delivered(r, "SIGNALLED", null)).toList());
            s.setRollbackOnly();
        });
        assertThat(claimed).allSatisfy(r -> assertThat(messageStatus(r.messageId())).isEqualTo("IN_FLIGHT"));
        expireMessages();
        List<MessageRow> recovered = claimMessages("B", 2);
        assertThat(recovered).hasSize(2).allMatch(r -> r.attempts() == 2);
        tx.executeWithoutResult(s -> store.completeMessages("B", recovered.stream()
                .map(r -> MessageCompletion.delivered(r, "SIGNALLED", null)).toList()));
        assertThat(recovered).allSatisfy(r -> assertThat(messageStatus(r.messageId())).isEqualTo("DELIVERED"));
    }

    @Test void messageRenewalExtendsOnlyTheCurrentInflightGeneration() {
        insertMessages("target-a", 1);
        MessageRow row = claimMessages("A", 1).getFirst();
        OffsetDateTime before = jdbc.queryForObject("SELECT lease_until FROM order_workflow_messages", OffsetDateTime.class);
        assertThat(tx.<List<String>>execute(s -> store.renewMessages("A", List.of(row), Duration.ofSeconds(60))))
                .containsExactly(row.messageId());
        OffsetDateTime after = jdbc.queryForObject("SELECT lease_until FROM order_workflow_messages", OffsetDateTime.class);
        assertThat(after).isAfter(before.plusSeconds(20));
        assertThat(tx.<List<String>>execute(s -> store.renewMessages("B", List.of(row), Duration.ofSeconds(60)))).isEmpty();
        tx.executeWithoutResult(s -> store.completeMessages("A", List.of(MessageCompletion.delivered(row, "SIGNALLED", null))));
        assertThat(tx.<List<String>>execute(s -> store.renewMessages("A", List.of(row), Duration.ofSeconds(60)))).isEmpty();
    }

    @Test void twoDispatchersDeliverEachMessageOnceInTargetOrderAndReclaimACrashedOwner() throws Exception {
        // A pod claimed three messages and died before acknowledging them.
        insertMessages("target-crashed", 3);
        List<MessageRow> abandoned = claimMessages("crashed-pod", 3);
        expireMessages();
        for (int t = 0; t < 6; t++) insertMessages("target-" + t, 6);
        insert(10);
        var sent = new ConcurrentHashMap<String, List<String>>();
        var active = new ConcurrentHashMap<String, AtomicInteger>();
        var overlap = new AtomicInteger();
        WorkflowDispatcher a = dispatcher("owner-a", recordingGateway("owner-a", sent, active, overlap));
        WorkflowDispatcher b = dispatcher("owner-b", recordingGateway("owner-b", sent, active, overlap));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { while (a.dispatchOnce() > 0) { } return true; });
            var second = pool.submit(() -> { while (b.dispatchOnce() > 0) { } return true; });
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        } finally { a.stop(); b.stop(); }
        assertThat(overlap.get()).isZero();
        List<String> all = sent.values().stream().flatMap(List::stream).toList();
        assertThat(all).hasSize(39).doesNotHaveDuplicates();
        assertThat(sent.keySet()).anyMatch(k -> k.startsWith("owner-a|")).anyMatch(k -> k.startsWith("owner-b|"));
        // Within one owner, a target's messages went out in created_at order.
        Map<String, Instant> created = new java.util.HashMap<>();
        jdbc.query("SELECT message_id, created_at FROM order_workflow_messages", rs -> {
            created.put(rs.getString(1), rs.getTimestamp(2).toInstant());
        });
        sent.values().forEach(ids -> assertThat(ids.stream().map(created::get).toList()).isSorted());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_workflow_messages WHERE status <> 'DELIVERED' OR lease_owner IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_workflow_dispatches WHERE status <> 'STARTED' OR lease_owner IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT attempts FROM order_workflow_messages WHERE target_workflow_id='target-crashed'", Integer.class))
                .containsOnly(2);
        // The dead pod's late result for its old generation is fenced out.
        assertThat(tx.<List<String>>execute(s -> store.completeMessages("crashed-pod", abandoned.stream()
                .map(r -> MessageCompletion.retry(r, "late", Duration.ofSeconds(1))).toList()))).isEmpty();
        assertThat(abandoned).allSatisfy(r -> assertThat(messageStatus(r.messageId())).isEqualTo("DELIVERED"));
    }

    private WorkflowDispatcher dispatcher(String owner, WorkflowGateway gateway) {
        var workers = new OrderProperties.Workers(true, owner, Duration.ofMillis(200), 5,
                Duration.ofSeconds(30), Duration.ofSeconds(30), 4, 50, Duration.ofMillis(20));
        var props = new OrderProperties(null, null, null, null, null, null, null, null, null, workers, null, null);
        return new WorkflowDispatcher(store, new StaticListableBeanFactory(Map.of("gateway", gateway))
                .getBeanProvider(WorkflowGateway.class), mock(ClosedTargetResolver.class), new DispatchNudger(), tx,
                new Json(new ObjectMapper()), props, new SimpleMeterRegistry());
    }

    /** Records sends per owner and target and counts any same-owner overlap on one target. */
    private static WorkflowGateway recordingGateway(String owner, Map<String, List<String>> sent,
                                                    Map<String, AtomicInteger> active, AtomicInteger overlap) {
        return new WorkflowGateway() {
            @Override public Result start(String workflowId, String workflowType, String taskQueue, WorkflowInput input) {
                return Result.STARTED;
            }
            @Override public Result signalWithStart(String workflowId, String workflowType, String taskQueue,
                                                    WorkflowInput input, WorkflowMessage message) {
                String key = owner + "|" + workflowId;
                if (active.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet() > 1) overlap.incrementAndGet();
                sent.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(message.messageId());
                try { Thread.sleep(5); return Result.SIGNALLED; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return Result.RETRY; }
                finally { active.get(key).decrementAndGet(); }
            }
        };
    }

    private final AtomicInteger messageSequence = new AtomicInteger();

    /** Distinct, increasing created_at so cross-pass ordering is well defined. */
    private void insertMessages(String target, int count) {
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < count; i++) {
                String id = target + "-" + UUID.randomUUID();
                store.insertMessage(id, 1L, target, "OrderCheckoutWorkflow", "queue", "{}",
                        WorkflowMessage.PAYMENT_RESULT, "{}", "a".repeat(64));
                jdbc.update("UPDATE order_workflow_messages SET created_at = TIMESTAMPTZ '2026-10-20 00:00:00+00' + ? * INTERVAL '1 millisecond' WHERE message_id = ?",
                        messageSequence.incrementAndGet(), id);
            }
        });
    }
    private List<MessageRow> claimMessages(String owner, int limit) {
        List<MessageRow> rows = new ArrayList<>(tx.execute(s -> store.claimMessages(owner, limit, Duration.ofSeconds(30))));
        rows.sort(java.util.Comparator.comparing(MessageRow::createdAt));
        return rows;
    }
    private void expireMessages() { jdbc.update("UPDATE order_workflow_messages SET lease_until=NOW()-INTERVAL '1 second'"); }
    private String messageStatus(String messageId) {
        return jdbc.queryForObject("SELECT status FROM order_workflow_messages WHERE message_id=?", String.class, messageId);
    }

    private void insert(int count) {
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < count; i++) store.insertDispatch(UUID.randomUUID(), null, "test-" + UUID.randomUUID(),
                    "OrderCheckoutWorkflow", "queue", "{}", "a".repeat(64));
        });
    }
    private List<DispatchRow> claim(String owner, int limit) {
        return tx.execute(s -> store.claimDispatches(owner, limit, Duration.ofSeconds(30)));
    }
    private void expire() { jdbc.update("UPDATE order_workflow_dispatches SET lease_until=NOW()-INTERVAL '1 second'"); }
    private String status(DispatchRow row) {
        return jdbc.queryForObject("SELECT status FROM order_workflow_dispatches WHERE dispatch_id=?", String.class, row.dispatchId());
    }
}
