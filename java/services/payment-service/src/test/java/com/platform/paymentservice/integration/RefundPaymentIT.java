package com.platform.paymentservice.integration;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.platform.paymentservice.application.CanonicalFingerprint;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.PaymentProviderPort;
import com.platform.paymentservice.application.port.out.ProviderOperationResult;
import com.platform.paymentservice.application.service.RefundPaymentCommand;
import com.platform.paymentservice.application.service.RefundPaymentResult;
import com.platform.paymentservice.application.service.RefundPaymentService;
import com.platform.paymentservice.domain.Money;
import com.platform.paymentservice.domain.ProviderOperation;
import com.platform.paymentservice.domain.RefundReason;
import com.platform.paymentservice.domain.ReturnScopeInspectionEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefundPaymentIT extends AbstractPaymentIT {
    private static final Instant NOW = Instant.parse("2026-09-23T08:00:00Z");
    private static final String PAYMENT = "TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABC";
    private static final String REFUND = "RFD_01K5V7M4R8Q2K7V6W5X1Y0ZABD";
    private static final String OPERATION = "POP_01K5V7M4R8Q2K7V6W5X1Y0ZABE";
    private static final String KEY = "refund-command-key-0001";

    @BeforeEach
    void seedPayment() {
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id,order_id,payment_attempt_number,user_id,amount_minor,currency,
                   provider_transaction_id,provider_charge_id,status,financial_state_version,
                   expires_at,succeeded_at)
                VALUES (?,99183,1,?,1500000,'VND','pi_refund','ch_refund','SUCCESS',1,?,?)
                """, PAYMENT, UUID.randomUUID(), Timestamp.from(NOW.plusSeconds(900)),
                Timestamp.from(NOW.minusSeconds(60)));
    }

    @Test
    void pending_refund_commits_tx1_calls_provider_outside_tx_and_commits_r1() {
        CapturingProvider provider = new CapturingProvider(new ProviderOperationResult.Confirmed(
                "re_pending", "req_refund", "{\"id\":\"re_pending\",\"status\":\"pending\"}",
                null, "pending", "ch_refund", 1_500_000L, "VND"));

        RefundPaymentResult result = service(provider).refund(command(KEY), "api-refund-1");

        assertThat(result).isEqualTo(new RefundPaymentResult.Processing(REFUND, PAYMENT));
        assertThat(provider.transactionWasActive).isFalse();
        assertThat(provider.operation.providerIdempotencyKey()).isEqualTo("pay:refund:" + REFUND);
        assertThat(provider.operation.requestFingerprintJson()).contains("\"provider_transaction_id\":\"pi_refund\"")
                .contains("\"refund_transaction_id\":\"" + REFUND + "\"");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, PAYMENT))
                .isEqualTo("REFUNDING");
        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT provider_refund_id FROM refund_transactions", String.class))
                .isEqualTo("re_pending");
        assertThat(jdbc.queryForObject("SELECT status FROM provider_operations", String.class))
                .isEqualTo("PROVIDER_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_idempotency", String.class)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history", Integer.class)).isEqualTo(2);
    }

    @Test
    void immediate_success_posts_balanced_refund_journal_and_replay_does_not_call_provider() {
        CapturingProvider provider = new CapturingProvider(new ProviderOperationResult.Confirmed(
                "re_success", "req_refund", "{\"id\":\"re_success\",\"status\":\"succeeded\"}",
                null, "succeeded", "ch_refund", 1_500_000L, "VND"));
        RefundPaymentService service = service(provider);

        assertThat(service.refund(command(KEY), "api-refund-1"))
                .isEqualTo(new RefundPaymentResult.Processing(REFUND, PAYMENT));
        assertThat(service.refund(command(KEY), "api-refund-2"))
                .isEqualTo(new RefundPaymentResult.Processing(REFUND, PAYMENT));

        assertThat(provider.calls).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions", String.class)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT financial_state_version FROM payment_transactions", Long.class))
                .isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT journal_type FROM financial_journals", String.class)).isEqualTo("REFUND");
        assertThat(jdbc.queryForObject("""
                SELECT SUM(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END)
                  FROM financial_ledger_entries
                """, Long.class)).isZero();
    }

    @Test
    void second_command_for_order_is_rejected_by_durable_one_refund_guard() {
        CapturingProvider provider = new CapturingProvider(new ProviderOperationResult.Confirmed(
                "re_pending", null, "{\"id\":\"re_pending\",\"status\":\"pending\"}",
                null, "pending", "ch_refund", 1_500_000L, "VND"));
        RefundPaymentService service = service(provider);
        assertThat(service.refund(command(KEY), "api-refund-1"))
                .isInstanceOf(RefundPaymentResult.Processing.class);

        assertThat(service.refund(command("refund-command-key-0002"), "api-refund-2"))
                .isInstanceOf(RefundPaymentResult.OrderRefundAlreadyExists.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_transactions", Integer.class)).isEqualTo(1);
        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void opaque_request_id_is_escaped_in_provider_metadata_and_replays_exactly() throws Exception {
        var provider = new CapturingProvider(new ProviderOperationResult.Confirmed(
                "re_pending", null, "{\"id\":\"re_pending\",\"status\":\"pending\"}",
                null, "pending", "ch_refund", 1_500_000L, "VND"));
        var original = command(KEY);
        String opaque = "refund-\"quoted\"-\\path\nline";
        var command = new RefundPaymentCommand(original.transactionId(), original.orderId(), opaque,
                original.amount(), original.reason(), original.evidence(), original.idempotencyKey(), original.correlationId());
        var service = service(provider);
        assertThat(service.refund(command, "first-worker")).isInstanceOf(RefundPaymentResult.Processing.class);
        assertThat(service.refund(command, "replay-worker")).isInstanceOf(RefundPaymentResult.Processing.class);
        assertThat(provider.calls).isEqualTo(1);
        var fingerprint = JsonMapper.builder().build().readTree(provider.operation.requestFingerprintJson());
        assertThat(fingerprint.path("metadata").path("refund_request_id").asText()).isEqualTo(opaque);
        assertThat(fingerprint.path("provider_transaction_id").asText()).isEqualTo("pi_refund");
    }

    private RefundPaymentService service(PaymentProviderPort provider) {
        return new RefundPaymentService(refundPayments, providerOperations, provider,
                new FixedIds(), (ClockPort) () -> NOW, new CanonicalFingerprint(),
                JsonMapper.builder().findAndAddModules().build(), Duration.ofSeconds(45),
                Duration.ofHours(23), Duration.ofDays(7), Duration.ofSeconds(30));
    }

    private static RefundPaymentCommand command(String key) {
        return new RefundPaymentCommand(PAYMENT, 99183, "refund-request-1",
                new Money(1_500_000, "VND"), RefundReason.CUSTOMER_RETURN_SELECTED_LINES,
                new ReturnScopeInspectionEvidence("scope-1", 2, 2, 1, 1), key, "workflow-99183");
    }

    private static final class CapturingProvider implements PaymentProviderPort {
        private final ProviderOperationResult result;
        private int calls;
        private boolean transactionWasActive;
        private ProviderOperation operation;
        private CapturingProvider(ProviderOperationResult result) { this.result = result; }
        @Override public ProviderOperationResult dispatch(ProviderOperation operation) {
            calls++; this.operation = operation;
            transactionWasActive = TransactionSynchronizationManager.isActualTransactionActive();
            return result;
        }
        @Override public ProviderOperationResult searchBeforeAnyRecreate(ProviderOperation operation) {
            return dispatch(operation);
        }
    }

    private static final class FixedIds implements IdGeneratorPort {
        private int events;
        private int journals;
        @Override public String nextPaymentTransactionId() { return "TXN_UNUSED"; }
        @Override public String nextRefundTransactionId() { return REFUND; }
        @Override public String nextProviderOperationId() { return OPERATION; }
        @Override public String nextReviewCaseId() { return "PRC_01K5V7M4R8Q2K7V6W5X1Y0ZABF"; }
        @Override public String nextFinancialJournalId() { return "FJN_01K5V7M4R8Q2K7V6W5X1Y0ZA" + (++journals); }
        @Override public UUID nextEventId() {
            return new UUID(0x0000000000004000L, 0x8000000000000000L + (++events));
        }
    }
}
