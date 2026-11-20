package com.platform.paymentservice.integration;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.service.IdempotencyExecutor;
import com.platform.paymentservice.application.service.ManualCorrectionService;
import com.platform.paymentservice.application.service.ReviewCaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ManualCorrectionIT extends AbstractPaymentIT {
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    @BeforeEach
    void seed() {
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id,order_id,payment_attempt_number,user_id,amount_minor,currency,
                   provider_transaction_id,status,expires_at,requires_manual_review)
                VALUES ('TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABE',901,1,?,5000,'VND','pi_manual','PENDING',
                        ?::timestamptz,true)
                """, UUID.randomUUID(), "2026-09-25T11:00:00Z");
        jdbc.update("""
                INSERT INTO payment_review_cases
                  (id,payment_transaction_id,reason_code,case_status,evidence,opened_by,
                   opened_at,assigned_to)
                VALUES ('PRC_01K5V7M4R8Q2K7V6W5X1Y0ZABF','TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABE','RECONCILIATION_DRIFT','IN_PROGRESS',
                        '[{"db_status":"PENDING","provider_status":"succeeded"}]',
                        'RECONCILIATION',?::timestamptz,'accounting-maker')
                """, "2026-09-25T09:00:00Z");
    }

    @Test
    void maker_checker_correction_uses_fsm_then_case_can_resolve_with_audit() {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        IdempotencyExecutor executor = new IdempotencyExecutor(idempotency, () -> NOW);
        ManualCorrectionService corrections = new ManualCorrectionService(reviewCases,
                webhookPayments, commits, executor, new FixedIds(), () -> NOW, mapper,
                Duration.ofSeconds(45), Duration.ofDays(7));
        ManualCorrectionService.Command command = new ManualCorrectionService.Command(
                "PRC_01K5V7M4R8Q2K7V6W5X1Y0ZABF", "PAYMENT", "TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABE", "PAYMENT_SUCCESS_CONFIRMED",
                5000, "VND", "succeeded", "ch_manual", "txn_manual",
                "{\"id\":\"pi_manual\",\"status\":\"succeeded\",\"latest_charge\":\"ch_manual\"}", "accounting-maker", "accounting-checker",
                "corr-manual", "manual-correction-key-0001");

        assertThat(corrections.correct(command, "ops-correction").outcome())
                .isEqualTo(ManualCorrectionService.Outcome.APPLIED);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id='TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABE'", String.class))
                .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT triggered_by FROM payment_status_history WHERE cause_ref='PRC_01K5V7M4R8Q2K7V6W5X1Y0ZABF'", String.class))
                .isEqualTo("MANUAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isEqualTo(1);

        ReviewCaseService reviews = new ReviewCaseService(reviewCases, executor, () -> NOW,
                mapper, Duration.ofSeconds(45), Duration.ofDays(7));
        assertThat(reviews.resolve("PRC_01K5V7M4R8Q2K7V6W5X1Y0ZABF", "accounting-maker", "accounting-checker",
                "corr-resolve", "manual-resolve-key-00001", "CORRECTION_APPLIED",
                "Provider fact verified and correction committed", "INC-901", "ops-resolve").outcome())
                .isEqualTo(ReviewCaseService.Outcome.OK);
        assertThat(jdbc.queryForObject("SELECT case_status FROM payment_review_cases WHERE id='PRC_01K5V7M4R8Q2K7V6W5X1Y0ZABF'", String.class))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT requires_manual_review FROM payment_transactions WHERE id='TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABE'", Boolean.class))
                .isFalse();
    }

    @Test
    void terminal_non_capture_requires_canceled_intent_proof_and_explicit_event_codes() throws Exception {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var service = new ManualCorrectionService(reviewCases, webhookPayments, commits,
                new IdempotencyExecutor(idempotency, () -> NOW), new FixedIds(), () -> NOW, mapper,
                Duration.ofSeconds(45), Duration.ofDays(7));
        var invalid = nonCapture("PAYMENT_FAILED_CONFIRMED", "requires_payment_method", "DECLINE_LIMIT", null, "manual-invalid-key-001");
        assertThat(service.correct(invalid, "ops").outcome()).isEqualTo(ManualCorrectionService.Outcome.INVALID_TRANSITION);
        var missing = nonCapture("PAYMENT_FAILED_CONFIRMED", "canceled", null, null, "manual-missing-key-001");
        assertThat(service.correct(missing, "ops").outcome()).isEqualTo(ManualCorrectionService.Outcome.INVALID_TRANSITION);
        var valid = nonCapture("PAYMENT_FAILED_CONFIRMED", "canceled", "DECLINE_LIMIT", null, "manual-valid-key-0001");
        assertThat(service.correct(valid, "ops").outcome()).isEqualTo(ManualCorrectionService.Outcome.APPLIED);
        var event = mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox_events", String.class));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "audit-payment-failed.json"), event.toString());
        assertThat(event.path("data").path("error_code").asText()).isEqualTo("DECLINE_LIMIT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isZero();
        var changed = new ManualCorrectionService.Command(valid.caseId(), valid.aggregateType(), valid.aggregateId(),
                valid.trigger(), valid.amountMinor(), valid.currency(), valid.providerStatus(), valid.providerObjectId(),
                null, "{\"id\":\"pi_manual\",\"status\":\"canceled\",\"reason\":\"different proof\"}",
                valid.actor(), valid.approver(), valid.correlationId(), valid.idempotencyKey(), valid.errorCode(), valid.reasonCode());
        assertThat(service.correct(changed, "ops").outcome()).isEqualTo(ManualCorrectionService.Outcome.KEY_REUSED);
    }

    @Test
    void canceled_correction_emits_reason_code() throws Exception {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var service = new ManualCorrectionService(reviewCases, webhookPayments, commits,
                new IdempotencyExecutor(idempotency, () -> NOW), new FixedIds(), () -> NOW, mapper,
                Duration.ofSeconds(45), Duration.ofDays(7));
        assertThat(service.correct(nonCapture("PAYMENT_CANCELED", "canceled", null,
                "BUYER_CANCELLED", "manual-cancel-key-0001"), "ops").outcome()).isEqualTo(ManualCorrectionService.Outcome.APPLIED);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "audit-payment-canceled.json"), jdbc.queryForObject("SELECT payload::text FROM outbox_events", String.class));
        assertThat(mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox_events", String.class))
                .path("data").path("reason_code").asText()).isEqualTo("BUYER_CANCELLED");
    }

    @Test
    void refund_processing_correction_advances_payment_financial_version() throws Exception {
        jdbc.update("UPDATE payment_transactions SET status='REFUNDING',succeeded_at=?::timestamptz,financial_state_version=2", NOW.toString());
        jdbc.update("""
                INSERT INTO refund_transactions
                  (id,payment_transaction_id,order_id,refund_request_id,command_idempotency_key,
                   refund_amount_minor,reason,inventory_evidence,status)
                VALUES ('RFD_01K5V7M4R8Q2K7V6W5X1Y0ZABG','TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABE',901,
                        'refund-manual','refund-command-0001',5000,'ADMIN_FORCE_REFUND_BEFORE_DISPATCH','{}','PENDING')
                """);
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var service = new ManualCorrectionService(reviewCases, webhookPayments, commits,
                new IdempotencyExecutor(idempotency, () -> NOW), new FixedIds(), () -> NOW, mapper,
                Duration.ofSeconds(45), Duration.ofDays(7));
        var command = new ManualCorrectionService.Command("PRC_01K5V7M4R8Q2K7V6W5X1Y0ZABF", "REFUND",
                "RFD_01K5V7M4R8Q2K7V6W5X1Y0ZABG", "REFUND_REQUEST_SENT", 5000, "VND", "pending", "re_manual",
                null, "{\"status\":\"pending\"}", "accounting-maker", "accounting-checker", "corr-refund", "manual-refund-key-0001");
        assertThat(service.correct(command, "ops").outcome()).isEqualTo(ManualCorrectionService.Outcome.APPLIED);
        assertThat(jdbc.queryForObject("SELECT financial_state_version FROM payment_transactions", Long.class)).isEqualTo(3);
        var payload = jdbc.queryForObject("SELECT payload::text FROM outbox_events", String.class);
        assertThat(mapper.readTree(payload).path("data").path("financial_state_version").asLong()).isEqualTo(3);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "audit-refund-processing.json"), payload);
    }

    @Test
    void success_correction_rejects_intent_as_charge_and_mismatched_provider_evidence() {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var service = new ManualCorrectionService(reviewCases, webhookPayments, commits,
                new IdempotencyExecutor(idempotency, () -> NOW), new FixedIds(), () -> NOW, mapper,
                Duration.ofSeconds(45), Duration.ofDays(7));
        for (String[] invalid : java.util.List.of(
                new String[]{"pi_manual", "pi_manual", "pi_manual"},
                new String[]{"ch_manual", "pi_other", "ch_manual"},
                new String[]{"ch_manual", "pi_manual", "ch_other"})) {
            var command = new ManualCorrectionService.Command(
                    "PRC_01K5V7M4R8Q2K7V6W5X1Y0ZABF", "PAYMENT", "TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABE",
                    "PAYMENT_SUCCESS_CONFIRMED", 5000, "VND", "succeeded", invalid[0], null,
                    "{\"id\":\"" + invalid[1] + "\",\"status\":\"succeeded\",\"latest_charge\":\"" + invalid[2] + "\"}",
                    "accounting-maker", "accounting-checker", "corr-success", "success-proof-" + invalid[0] + invalid[1] + invalid[2]);
            assertThat(service.correct(command, "ops").outcome()).isEqualTo(ManualCorrectionService.Outcome.INVALID_TRANSITION);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void provider_object_id_cannot_exceed_database_identity_capacity() {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var service = new ManualCorrectionService(reviewCases, webhookPayments, commits,
                new IdempotencyExecutor(idempotency, () -> NOW), new FixedIds(), () -> NOW, mapper,
                Duration.ofSeconds(45), Duration.ofDays(7));
        var command = new ManualCorrectionService.Command(
                "PRC_01K5V7M4R8Q2K7V6W5X1Y0ZABF", "PAYMENT", "TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABE",
                "PAYMENT_SUCCESS_CONFIRMED", 5000, "VND", "succeeded", "ch_" + "x".repeat(98), null,
                "{\"status\":\"succeeded\"}", "accounting-maker", "accounting-checker", "corr-length", "manual-length-key-0001");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.correct(command, "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isZero();
    }

    private ManualCorrectionService.Command nonCapture(String trigger, String status,
                                                        String error, String reason, String key) {
        return new ManualCorrectionService.Command("PRC_01K5V7M4R8Q2K7V6W5X1Y0ZABF", "PAYMENT", "TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABE", trigger,
                5000, "VND", status, "pi_manual", null,
                "{\"id\":\"pi_manual\",\"status\":\"" + status + "\"}", "accounting-maker", "accounting-checker",
                "corr-manual", key, error, reason);
    }

    private static final class FixedIds implements IdGeneratorPort {
        private final AtomicInteger events = new AtomicInteger();
        @Override public String nextPaymentTransactionId() { return "unused"; }
        @Override public String nextProviderOperationId() { return "unused"; }
        @Override public String nextReviewCaseId() { return "unused"; }
        @Override public String nextFinancialJournalId() { return "FJN_MANUAL"; }
        @Override public UUID nextEventId() {
            return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", events.incrementAndGet()));
        }
    }
}
