package com.ecommerce.order.integration;

import com.ecommerce.order.common.ApiException;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.config.SubmissionWaiterProperties;
import com.ecommerce.order.service.SubmissionNotificationListener;
import com.ecommerce.order.service.SubmissionWaiters;
import com.ecommerce.order.store.SubmissionStore;
import com.ecommerce.order.support.AbstractIntegrationTest;
import com.ecommerce.order.support.Fixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.async.DeferredResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL commit/rollback delivery and independent replica-local waiter registries. */
class SubmissionNotificationIT extends AbstractIntegrationTest {
    @Autowired SubmissionStore submissions;
    @Autowired TransactionTemplate tx;
    @Autowired DataSource dataSource;
    @Autowired DataSourceProperties database;
    @Autowired OrderProperties properties;

    private UUID insert() {
        UUID token = UUID.randomUUID();
        submissions.insertIfAbsent(token, UUID.randomUUID(), "a".repeat(64), "{}");
        return token;
    }

    @Test
    void reconnectReadsAChangeCommittedWhileItsOwnListenerWasDisconnected() throws Exception {
        SubmissionWaiterProperties config = new SubmissionWaiterProperties(4, 2, 4,
                Duration.ofHours(1), Duration.ofMillis(500));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        SubmissionWaiters local = new SubmissionWaiters(submissions, config, properties, metrics);
        // URL application name isolates the exact session owned by this test. Never terminate
        // any other replica/test session, and never stop the shared PostgreSQL server.
        String app = "submission-reconnect-test-" + UUID.randomUUID();
        DataSourceProperties isolated = new DataSourceProperties();
        String url = database.determineUrl();
        isolated.setUrl(url + (url.contains("?") ? "&" : "?") + "ApplicationName=" + app);
        isolated.setUsername(database.determineUsername());
        isolated.setPassword(database.determinePassword());
        SubmissionNotificationListener listener = new SubmissionNotificationListener(isolated, local, config, metrics);
        try {
            listener.start();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(metrics.get("order.checkout.notification.connected").gauge().value()).isEqualTo(1));
            UUID token = insert();
            DeferredResult<?> response = local.await(token, Duration.ofSeconds(10), null,
                    row -> ResponseEntity.status(409).body(row.state()));
            Integer pid = jdbc.queryForObject("SELECT pid FROM pg_stat_activity WHERE application_name = ?",
                    Integer.class, app);
            assertThat(jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, pid)).isTrue();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(10))
                    .untilAsserted(() -> assertThat(metrics.get("order.checkout.notification.connected").gauge().value()).isZero());
            submissions.transition(token, "FAILED", "{}");
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(6)).until(response::hasResult);
            assertThat(((ResponseEntity<?>) response.getResult()).getBody()).isEqualTo("FAILED");
        } finally {
            listener.close();
            local.close();
            metrics.close();
        }
    }

    @Test
    void notificationIsDeliveredOnlyOnCommitAndOnlyForAStateTransition() throws Exception {
        UUID token = insert();
        try (Connection listener = dataSource.getConnection()) {
            listener.setAutoCommit(true);
            try (var statement = listener.createStatement()) { statement.execute("LISTEN order_submission_changed"); }
            PGConnection pg = listener.unwrap(PGConnection.class);
            tx.executeWithoutResult(status -> {
                assertThat(submissions.transition(token, "FAILED", "{}" )).isTrue();
                try { assertThat(pg.getNotifications(100)).isNullOrEmpty(); }
                catch (Exception e) { throw new AssertionError(e); }
                status.setRollbackOnly();
            });
            assertThat(pg.getNotifications(100)).isNullOrEmpty();
            assertThat(submissions.checkpoint(token).orElseThrow().state()).isEqualTo("PREPARING");
            assertThat(submissions.transition(token, "FAILED", "{}")).isTrue();
            PGNotification[] notifications = pg.getNotifications(2000);
            assertThat(notifications).anyMatch(n -> n.getParameter().equals(token.toString()));
            assertThat(submissions.transition(token, "PAYMENT_READY", null)).isFalse();
            assertThat(pg.getNotifications(100)).isNullOrEmpty();
        }
    }

    @Test
    void committedFailureWakesBothReplicaRegistriesWithoutPeriodicPolling() throws Exception {
        SubmissionWaiterProperties config = new SubmissionWaiterProperties(8, 2, 4,
                Duration.ofHours(1), Duration.ofMillis(100));
        SimpleMeterRegistry firstMeters = new SimpleMeterRegistry();
        SimpleMeterRegistry secondMeters = new SimpleMeterRegistry();
        try {
            SubmissionWaiters first = new SubmissionWaiters(submissions, config, properties, firstMeters);
            SubmissionWaiters second = new SubmissionWaiters(submissions, config, properties, secondMeters);
            SubmissionNotificationListener firstListener = new SubmissionNotificationListener(database, first, config, firstMeters);
            SubmissionNotificationListener secondListener = new SubmissionNotificationListener(database, second, config, secondMeters);
            try {
                firstListener.start();
                secondListener.start();
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(firstMeters.get("order.checkout.notification.connected").gauge().value()).isEqualTo(1);
                    assertThat(secondMeters.get("order.checkout.notification.connected").gauge().value()).isEqualTo(1);
                });
                UUID token = insert();
                DeferredResult<?> one = first.await(token, Duration.ofSeconds(5), null,
                        row -> ResponseEntity.status(409).body(row.state()));
                DeferredResult<?> two = second.await(token, Duration.ofSeconds(5), null,
                        row -> ResponseEntity.status(409).body(row.state()));
                submissions.transition(token, "RECALCULATION_REQUIRED", "{}");
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(4)).until(() -> one.hasResult() && two.hasResult());
                assertThat(((ResponseEntity<?>) one.getResult()).getBody()).isEqualTo("RECALCULATION_REQUIRED");
                assertThat(((ResponseEntity<?>) two.getResult()).getBody()).isEqualTo("RECALCULATION_REQUIRED");
            } finally {
                firstListener.close();
                secondListener.close();
                first.close();
                second.close();
            }
        } finally {
            firstMeters.close();
            secondMeters.close();
        }
    }

    @Test
    void httpThreadIsReleasedWhilePreparingAndDeadlineStillReturnsTheContractProblem() throws Exception {
        UUID token = UUID.randomUUID();
        // A valid persisted request without dispatch represents an accepted but stalled workflow.
        var request = Fixtures.submission(token.toString(), UUID.randomUUID().toString(), Fixtures.defaultLines(), 0, null);
        var tree = mapper.valueToTree(request);
        com.ecommerce.order.common.Json json = new com.ecommerce.order.common.Json(mapper);
        submissions.insertIfAbsent(token, UUID.fromString(request.userId()), json.digest(tree), mapper.writeValueAsString(request));
        MvcResult initial = mvc.perform(MockMvcRequestBuilders.post("/internal/v1/checkout-order-submissions")
                .header("X-Service-Identity", "checkout-service")
                .header("Idempotency-Key", token.toString())
                .header("X-Request-Id", "submission-async-deadline")
                .header("X-Request-Timeout-Ms", "1200")
                .contentType("application/json").content(mapper.writeValueAsString(request))).andReturn();
        assertThat(initial.getRequest().isAsyncStarted()).isTrue();
        assertThat(initial.getResponse().isCommitted()).isFalse();
        assertThat(initial.getAsyncResult(3000)).isInstanceOf(ApiException.class);
        MvcResult finished = mvc.perform(MockMvcRequestBuilders.asyncDispatch(initial)).andReturn();
        assertThat(finished.getResponse().getStatus()).isEqualTo(503);
        assertThat(finished.getResponse().getHeader("Retry-After")).isEqualTo("2");
        assertThat(finished.getResponse().getHeader("X-Request-Id")).isEqualTo("submission-async-deadline");
        assertThat(mapper.readTree(finished.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("SUBMISSION_IN_PROGRESS");
        assertThat(submissions.checkpoint(token).orElseThrow().state()).isEqualTo("PREPARING");
    }
}
