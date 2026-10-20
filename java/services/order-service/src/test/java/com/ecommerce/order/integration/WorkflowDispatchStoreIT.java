package com.ecommerce.order.integration;

import com.ecommerce.order.store.WorkflowOutboxStore;
import com.ecommerce.order.store.WorkflowOutboxStore.DispatchCompletion;
import com.ecommerce.order.store.WorkflowOutboxStore.DispatchRow;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL coverage for batched ACK, claim generations, rollback and SKIP LOCKED. */
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

    @BeforeEach void reset() { jdbc.execute("TRUNCATE order_workflow_dispatches"); }
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
