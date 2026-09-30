package com.platform.paymentservice.integration;

import com.platform.paymentservice.application.service.CancelPaymentService;
import com.platform.paymentservice.application.service.DeclinePaymentService;
import com.platform.paymentservice.application.service.InitiatePaymentService;
import com.platform.paymentservice.application.service.PaymentClientDataService;
import com.platform.paymentservice.application.service.WebhookIngressService;
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

import static org.assertj.core.api.Assertions.assertThat;

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
}
