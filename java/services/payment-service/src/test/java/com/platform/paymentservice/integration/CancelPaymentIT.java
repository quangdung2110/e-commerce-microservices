package com.platform.paymentservice.integration;

import com.platform.paymentservice.application.CanonicalFingerprint;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.PaymentProviderPort;
import com.platform.paymentservice.application.port.out.ProviderOperationResult;
import com.platform.paymentservice.application.service.CancelPaymentCommand;
import com.platform.paymentservice.application.service.CancelPaymentResult;
import com.platform.paymentservice.application.service.CancelPaymentService;
import com.platform.paymentservice.application.service.DeclinePaymentCommand;
import com.platform.paymentservice.application.service.DeclinePaymentResult;
import com.platform.paymentservice.application.service.DeclinePaymentService;
import com.platform.paymentservice.domain.DeclinePolicy;
import com.platform.paymentservice.domain.ProviderOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CancelPaymentIT extends AbstractPaymentIT {
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final String TXN = "TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABE";
    private static final String OP = "POP_01K5V7M4R8Q2K7V6W5X1Y0ZABF";
    private static final String REVIEW = "PRC_01K5V7M4R8Q2K7V6W5X1Y0ZABG";
    private static final String KEY = "order-10001-cancel";
    private static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-000000000456");

    @Test
    void webhook_decline_commits_t3a_checkpoint_and_inbox_completion_atomically() {
        jdbc.update("""
                INSERT INTO webhook_logs
                  (stripe_event_id,event_type,event_created_at,livemode,raw_body_sha256,
                   signing_secret_id,verified_at,status,processing_attempts,lease_owner,
                   lease_token,lease_expires_at,received_at)
                VALUES ('evt_decline_atomic','payment_intent.payment_failed',?,false,repeat('a',64),
                        'key-1',?,'PROCESSING',1,'webhook-worker',7,?,?)
                """, java.sql.Timestamp.from(NOW.minusSeconds(1)), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW.plusSeconds(60)), java.sql.Timestamp.from(NOW));
        Long inboxId = jdbc.queryForObject(
                "SELECT id FROM webhook_logs WHERE stripe_event_id='evt_decline_atomic'", Long.class);

        var result = cancelPayments.prepareDecline(new com.platform.paymentservice.application.port.out.CancelPaymentPort.DeclineRecord(
                        TXN, "generic_decline", "card was declined", "issuer_declined",
                        false, 5, "evt_decline_atomic", "{}", inboxId, 7L,
                        NOW.minusSeconds(1)), OP, "webhook-worker", NOW,
                Duration.ofSeconds(45), NOW.plus(Duration.ofDays(7)));

        assertThat(result).isEqualTo(new com.platform.paymentservice.application.port.out.CancelPaymentPreparation.DeclineRecorded(1, false));
        assertThat(jdbc.queryForMap("SELECT status,last_provider_event_id,decline_count FROM payment_transactions WHERE id=?", TXN))
                .containsEntry("status", "PENDING")
                .containsEntry("last_provider_event_id", "evt_decline_atomic")
                .containsEntry("decline_count", 1);
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_logs WHERE id=?", String.class, inboxId))
                .isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("SELECT transition_id FROM payment_status_history", String.class))
                .isEqualTo("T3a");
    }

    @BeforeEach
    void seedPayment() {
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id,order_id,payment_attempt_number,user_id,amount_minor,currency,
                   provider_transaction_id,provider_status_snapshot,status,expires_at,
                   client_secret_ciphertext,client_secret_key_id,client_expires_at,
                   financial_state_version,version)
                VALUES (?,10001,1,?,995000,'VND','pi_cancel','requires_payment_method',
                        'PENDING',?,decode('010203','hex'),'local-v1',?,1,1)
                """, TXN, UUID.randomUUID(),
                java.sql.Timestamp.from(NOW.plus(Duration.ofMinutes(15))),
                java.sql.Timestamp.from(NOW.plus(Duration.ofMinutes(14))));
    }

    @Test
    void order_cancel_commits_provider_fact_history_outbox_and_idempotency_atomically() {
        CapturingProvider provider = providerFact("canceled", null);
        CancelPaymentService service = service(provider);

        CancelPaymentResult result = service.cancel(command(KEY), "api-1");

        assertThat(result).isInstanceOf(CancelPaymentResult.Canceled.class);
        assertThat(provider.calls).isEqualTo(1);
        assertThat(provider.transactionWasActive).isFalse();
        assertThat(provider.operation.providerIdempotencyKey()).isEqualTo("pay:cancel:" + TXN);
        assertThat(provider.operation.requestFingerprintJson())
                .contains("ORDER_COMMAND:PAYMENT_TIMEOUT", "pi_cancel");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT provider_status_snapshot FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("canceled");
        assertThat(jdbc.queryForObject("SELECT financial_state_version FROM payment_transactions WHERE id=?", Long.class, TXN))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT client_secret_ciphertext IS NULL FROM payment_transactions WHERE id=?", Boolean.class, TXN))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM provider_operations WHERE id=?", String.class, OP))
                .isEqualTo("PROVIDER_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_idempotency WHERE idempotency_key=? AND action='CANCEL'", String.class, KEY))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT response_body->>'outcome' FROM payment_idempotency WHERE idempotency_key=? AND action='CANCEL'", String.class, KEY))
                .isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT transition_id FROM payment_status_history", String.class)).isEqualTo("T5");
        assertThat(jdbc.queryForObject("SELECT payload->'data'->>'reason_code' FROM outbox_events", String.class))
                .isEqualTo("PAYMENT_TIMEOUT");
        assertThat(jdbc.queryForObject("SELECT partition_key FROM outbox_events", String.class)).isEqualTo("10001");

        assertThat(service.cancel(command(KEY), "api-2"))
                .isInstanceOf(CancelPaymentResult.Canceled.class);
        assertThat(provider.calls).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    @Test
    void capture_wins_cancel_race_and_opens_review_without_ever_canceling_success() {
        CapturingProvider provider = providerFact("succeeded", "ch_race");

        CancelPaymentResult result = service(provider).cancel(command(KEY), "api-1");

        assertThat(result).isInstanceOf(CancelPaymentResult.AlreadySucceeded.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT provider_charge_id FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("ch_race");
        assertThat(jdbc.queryForObject("SELECT reason_code FROM payment_review_cases WHERE id=?", String.class, REVIEW))
                .isEqualTo("CAPTURE_AFTER_CANCEL");
        assertThat(jdbc.queryForObject("SELECT requires_manual_review FROM payment_transactions WHERE id=?", Boolean.class, TXN))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT transition_id FROM payment_status_history", String.class)).isEqualTo("T2");
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_events", String.class))
                .isEqualTo("com.ecommerce.payment.transaction.succeeded.v2");
        assertThat(jdbc.queryForObject("SELECT response_body->>'outcome' FROM payment_idempotency WHERE idempotency_key=? AND action='CANCEL'", String.class, KEY))
                .isEqualTo("ALREADY_SUCCEEDED");
    }

    @Test
    void processing_or_ambiguous_provider_outcome_remains_recoverable() {
        jdbc.update("UPDATE payment_transactions SET status='PROCESSING' WHERE id=?", TXN);
        CapturingProvider provider = new CapturingProvider(new ProviderOperationResult.Unknown(
                "req_processing", "{\"id\":\"pi_cancel\",\"status\":\"processing\"}"));

        CancelPaymentResult result = service(provider).cancel(command(KEY), "api-1");

        assertThat(result).isInstanceOf(CancelPaymentResult.ProviderOutcomeUnknown.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT status FROM provider_operations WHERE id=?", String.class, OP))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_idempotency WHERE idempotency_key=? AND action='CANCEL'", String.class, KEY))
                .isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void provider_amount_mismatch_opens_review_without_changing_financial_state() {
        CapturingProvider provider = new CapturingProvider(new ProviderOperationResult.Confirmed(
                "pi_cancel", "req_mismatch",
                "{\"id\":\"pi_cancel\",\"status\":\"succeeded\",\"amount_minor\":1,\"currency\":\"VND\"}",
                null, "succeeded", "ch_wrong", 1L, "VND"));

        CancelPaymentResult result = service(provider).cancel(command(KEY), "api-1");

        assertThat(result).isInstanceOf(CancelPaymentResult.ProviderOutcomeUnknown.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT financial_state_version FROM payment_transactions WHERE id=?", Long.class, TXN))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT requires_manual_review FROM payment_transactions WHERE id=?", Boolean.class, TXN))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM provider_operations WHERE id=?", String.class, OP))
                .isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT reason_code FROM payment_review_cases WHERE id=?", String.class, REVIEW))
                .isEqualTo("AMOUNT_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void retryable_decline_is_audit_only_and_keeps_the_same_intent_pending() {
        CapturingProvider provider = providerFact("canceled", null);

        DeclinePaymentResult result = declineService(provider).record(
                decline("generic_decline"), "webhook-1");

        assertThat(result).isEqualTo(new DeclinePaymentResult.RetryableRecorded(1));
        assertThat(provider.calls).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("generic_decline");
        assertThat(jdbc.queryForObject("SELECT decline_count FROM payment_transactions WHERE id=?", Integer.class, TXN))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT financial_state_version FROM payment_transactions WHERE id=?", Long.class, TXN))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT transition_id FROM payment_status_history", String.class)).isEqualTo("T3a");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_operations", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void non_retryable_decline_cancels_provider_before_failed_fact() {
        CapturingProvider provider = providerFact("canceled", null);

        DeclinePaymentResult result = declineService(provider).record(
                decline("stolen_card"), "webhook-1");

        assertThat(result).isInstanceOf(DeclinePaymentResult.CancelResolution.class);
        assertThat(((DeclinePaymentResult.CancelResolution) result).result())
                .isInstanceOf(CancelPaymentResult.AlreadyNonCaptured.class);
        assertThat(provider.operation.requestFingerprintJson()).contains("DECLINED_NON_RETRYABLE");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForList("SELECT transition_id FROM payment_status_history ORDER BY id", String.class))
                .containsExactly("T3a", "T3");
        assertThat(jdbc.queryForObject("SELECT payload->'data'->>'error_code' FROM outbox_events", String.class))
                .isEqualTo("STOLEN_CARD");
        assertThat(jdbc.queryForObject("SELECT provider_status_snapshot FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("canceled");
    }

    @Test
    void sixth_retryable_decline_uses_limit_reason_and_error_code() {
        jdbc.update("UPDATE payment_transactions SET decline_count=5 WHERE id=?", TXN);
        CapturingProvider provider = providerFact("canceled", null);

        declineService(provider).record(decline("generic_decline"), "webhook-1");

        assertThat(provider.operation.requestFingerprintJson()).contains("DECLINE_LIMIT");
        assertThat(jdbc.queryForObject("SELECT decline_count FROM payment_transactions WHERE id=?", Integer.class, TXN))
                .isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT payload->'data'->>'error_code' FROM outbox_events", String.class))
                .isEqualTo("DECLINE_LIMIT_EXCEEDED");
    }

    @Test
    void expiration_scanner_uses_skip_locked_operation_and_only_expires_after_provider_canceled_fact() {
        jdbc.update("UPDATE payment_transactions SET expires_at=? WHERE id=?",
                java.sql.Timestamp.from(NOW.minusSeconds(1)), TXN);
        CapturingProvider provider = providerFact("canceled", null);

        int claimed = service(provider).expireDue("expiration-1", 10);

        assertThat(claimed).isEqualTo(1);
        assertThat(provider.operation.requestFingerprintJson()).contains("SESSION_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT transition_id FROM payment_status_history", String.class)).isEqualTo("T4");
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_events", String.class))
                .isEqualTo("com.ecommerce.payment.transaction.expired.v2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_idempotency", Integer.class)).isZero();
    }

    @Test
    void expiration_does_not_take_over_an_order_owned_cancel_operation() {
        jdbc.update("UPDATE payment_transactions SET expires_at=? WHERE id=?",
                java.sql.Timestamp.from(NOW.minusSeconds(1)), TXN);
        CapturingProvider provider = new CapturingProvider(new ProviderOperationResult.Unknown(
                "req_unknown", "{\"status\":\"processing\"}"));
        CancelPaymentService service = service(provider);

        assertThat(service.cancel(command(KEY), "api-1"))
                .isInstanceOf(CancelPaymentResult.ProviderOutcomeUnknown.class);
        assertThat(service.expireDue("expiration-1", 10)).isZero();
        assertThat(provider.calls).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT command_idempotency_key FROM provider_operations WHERE id=?", String.class, OP))
                .isEqualTo(KEY);
    }

    @Test
    void order_reuses_due_expiration_operation_and_gets_already_non_captured() {
        jdbc.update("UPDATE payment_transactions SET expires_at=? WHERE id=?",
                java.sql.Timestamp.from(NOW.minusSeconds(1)), TXN);
        MutableClock clock = new MutableClock(NOW);
        SequencedCancelProvider provider = new SequencedCancelProvider();
        CancelPaymentService service = service(provider, clock);

        assertThat(service.expireDue("expiration-1", 10)).isEqualTo(1);
        clock.now = NOW.plusSeconds(31);
        CancelPaymentResult result = service.cancel(command(KEY), "api-1");

        assertThat(result).isInstanceOf(CancelPaymentResult.AlreadyNonCaptured.class);
        assertThat(provider.calls).isEqualTo(2);
        assertThat(provider.firstOperationId).isEqualTo(provider.secondOperationId).isEqualTo(OP);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT response_body->>'outcome' FROM payment_idempotency WHERE idempotency_key=? AND action='CANCEL'", String.class, KEY))
                .isEqualTo("ALREADY_NON_CAPTURED");
    }

    @Test
    void terminal_non_capture_and_order_mismatch_do_not_call_provider() {
        jdbc.update("UPDATE payment_transactions SET status='EXPIRED', terminal_at=? WHERE id=?",
                java.sql.Timestamp.from(NOW), TXN);
        CapturingProvider provider = providerFact("canceled", null);
        CancelPaymentService service = service(provider);

        assertThat(service.cancel(command(KEY), "api-1"))
                .isInstanceOf(CancelPaymentResult.AlreadyNonCaptured.class);
        assertThat(provider.calls).isZero();
        assertThat(service.cancel(new CancelPaymentCommand(
                TXN, 999, CancelPaymentCommand.Reason.PAYMENT_TIMEOUT,
                "other-key", "corr-2"), "api-2"))
                .isInstanceOf(CancelPaymentResult.OrderMismatch.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_idempotency WHERE idempotency_key='other-key'", Integer.class))
                .isZero();
    }

    private CancelPaymentService service(PaymentProviderPort provider) {
        return service(provider, new FixedClock());
    }

    private CancelPaymentService service(PaymentProviderPort provider, ClockPort clock) {
        return new CancelPaymentService(cancelPayments, providerOperations, provider,
                new FixedIds(), clock, new CanonicalFingerprint(),
                Duration.ofSeconds(45), Duration.ofHours(23), Duration.ofDays(7), Duration.ofSeconds(30));
    }

    private DeclinePaymentService declineService(PaymentProviderPort provider) {
        CancelPaymentService cancels = service(provider);
        return new DeclinePaymentService(cancelPayments, cancels, new FixedIds(),
                new FixedClock(), new DeclinePolicy(5), Duration.ofSeconds(45), Duration.ofDays(7));
    }

    private static CancelPaymentCommand command(String key) {
        return new CancelPaymentCommand(TXN, 10001, CancelPaymentCommand.Reason.PAYMENT_TIMEOUT,
                key, "order-cancel-1");
    }

    private static DeclinePaymentCommand decline(String code) {
        return new DeclinePaymentCommand(TXN, code, "card was declined", "issuer_declined",
                "evt_decline_1", "{\"id\":\"pi_cancel\",\"status\":\"requires_payment_method\"}");
    }

    private static CapturingProvider providerFact(String status, String chargeId) {
        return new CapturingProvider(new ProviderOperationResult.Confirmed(
                "pi_cancel", "req_cancel", "{\"id\":\"pi_cancel\",\"status\":\"" + status + "\"}",
                null, status, chargeId, 995000L, "VND"));
    }

    private static final class FixedClock implements ClockPort {
        @Override public Instant now() { return NOW; }
    }

    private static final class MutableClock implements ClockPort {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public Instant now() { return now; }
    }

    private static final class FixedIds implements IdGeneratorPort {
        @Override public String nextPaymentTransactionId() { return TXN; }
        @Override public String nextProviderOperationId() { return OP; }
        @Override public String nextReviewCaseId() { return REVIEW; }
        @Override public UUID nextEventId() { return EVENT; }
    }

    private static final class CapturingProvider implements PaymentProviderPort {
        private final ProviderOperationResult result;
        private int calls;
        private boolean transactionWasActive;
        private ProviderOperation operation;
        private CapturingProvider(ProviderOperationResult result) { this.result = result; }
        @Override public ProviderOperationResult dispatch(ProviderOperation operation) {
            calls++;
            this.operation = operation;
            transactionWasActive = TransactionSynchronizationManager.isActualTransactionActive();
            return result;
        }
        @Override public ProviderOperationResult searchBeforeAnyRecreate(ProviderOperation operation) {
            throw new AssertionError("first dispatch must be inside the 23-hour horizon");
        }
    }

    private static final class SequencedCancelProvider implements PaymentProviderPort {
        private int calls;
        private String firstOperationId;
        private String secondOperationId;
        @Override public ProviderOperationResult dispatch(ProviderOperation operation) {
            calls++;
            if (calls == 1) {
                firstOperationId = operation.id();
                return new ProviderOperationResult.Unknown("req_unknown", "{\"status\":\"processing\"}");
            }
            secondOperationId = operation.id();
            return new ProviderOperationResult.Confirmed(
                    "pi_cancel", "req_canceled", "{\"id\":\"pi_cancel\",\"status\":\"canceled\"}",
                    null, "canceled", null, 995000L, "VND");
        }
        @Override public ProviderOperationResult searchBeforeAnyRecreate(ProviderOperation operation) {
            throw new AssertionError("recovery remains inside the safety horizon");
        }
    }
}
