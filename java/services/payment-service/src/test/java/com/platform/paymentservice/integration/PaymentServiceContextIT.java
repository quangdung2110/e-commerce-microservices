package com.platform.paymentservice.integration;

import com.platform.paymentservice.application.service.CancelPaymentService;
import com.platform.paymentservice.application.service.DeclinePaymentService;
import com.platform.paymentservice.application.service.InitiatePaymentService;
import com.platform.paymentservice.application.service.PaymentClientDataService;
import com.platform.paymentservice.application.service.WebhookIngressService;
import com.platform.paymentservice.application.port.out.ReconciliationPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PaymentServiceContextIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_service")
            .withUsername("payment")
            .withPassword("payment");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("payment.stripe.secret-key", () -> "sk_test_context_only");
        registry.add("payment.stripe.publishable-key", () -> "pk_test_context_only");
        registry.add("payment.stripe.webhook-secrets", () -> "whsec_context_only");
        registry.add("payment.webhook.processor-enabled", () -> "false");
        registry.add("payment.scheduling.enabled", () -> "false");
        registry.add("payment.crypto.local-key",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired InitiatePaymentService initiatePaymentService;
    @Autowired PaymentClientDataService paymentClientDataService;
    @Autowired CancelPaymentService cancelPaymentService;
    @Autowired DeclinePaymentService declinePaymentService;
    @Autowired WebhookIngressService webhookIngressService;
    @Autowired ReconciliationPort reconciliation;

    @Test
    void starts_spring_boot_runs_flyway_and_validates_the_canonical_schema() {
        Integer tableCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name IN
                 ('payment_transactions','refund_transactions','payment_disputes','provider_operations',
                  'webhook_logs','payment_status_history','outbox_events','payment_idempotency',
                  'payment_review_cases','reconciliation_runs','financial_journals','financial_ledger_entries')
                """, Integer.class);
        assertThat(tableCount).isEqualTo(12);
        assertThat(initiatePaymentService).isNotNull();
        assertThat(paymentClientDataService).isNotNull();
        assertThat(cancelPaymentService).isNotNull();
        assertThat(declinePaymentService).isNotNull();
        assertThat(webhookIngressService).isNotNull();
    }

    @Test
    void reconciliation_ownership_and_rollback_work_with_the_boot_transaction_manager() {
        Instant now = Instant.now();
        for (String lane : new String[]{"OBJECT_STATE", "BALANCE_LEDGER"}) {
            var run = reconciliation.start(lane, now.minusSeconds(60), now, false, now).orElseThrow();
            assertThat(reconciliation.owned(run.id(), () -> jdbc.queryForObject(
                    "SELECT status FROM reconciliation_runs WHERE id=?", String.class, run.id())))
                    .isEqualTo("RUNNING");
            assertThatThrownBy(() -> reconciliation.owned(run.id(), () -> {
                reconciliation.setFreeze(true, "context-fixture", "must-rollback", now);
                throw new IllegalStateException("fixture rollback");
            })).isInstanceOf(IllegalStateException.class).hasMessage("fixture rollback");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_runs WHERE lane='CONTROL'", Integer.class)).isZero();
            reconciliation.complete(run.id(), null, ReconciliationPort.Counts.zero(), now);
            assertThat(jdbc.queryForObject("SELECT status FROM reconciliation_runs WHERE id=?", String.class, run.id()))
                    .isEqualTo("COMPLETED");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND classid=731024 AND granted", Integer.class)).isZero();
    }
}
