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
                VALUES ('TXN_MANUAL',901,1,?,5000,'VND','pi_manual','PENDING',
                        ?::timestamptz,true)
                """, UUID.randomUUID(), "2026-09-25T11:00:00Z");
        jdbc.update("""
                INSERT INTO payment_review_cases
                  (id,payment_transaction_id,reason_code,case_status,evidence,opened_by,
                   opened_at,assigned_to)
                VALUES ('PRC_MANUAL','TXN_MANUAL','RECONCILIATION_DRIFT','IN_PROGRESS',
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
                "PRC_MANUAL", "PAYMENT", "TXN_MANUAL", "PAYMENT_SUCCESS_CONFIRMED",
                5000, "VND", "succeeded", "ch_manual", "txn_manual",
                "{\"status\":\"succeeded\"}", "accounting-maker", "accounting-checker",
                "corr-manual", "manual-correction-key-0001");

        assertThat(corrections.correct(command, "ops-correction").outcome())
                .isEqualTo(ManualCorrectionService.Outcome.APPLIED);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id='TXN_MANUAL'", String.class))
                .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT triggered_by FROM payment_status_history WHERE cause_ref='PRC_MANUAL'", String.class))
                .isEqualTo("MANUAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isEqualTo(1);

        ReviewCaseService reviews = new ReviewCaseService(reviewCases, executor, () -> NOW,
                mapper, Duration.ofSeconds(45), Duration.ofDays(7));
        assertThat(reviews.resolve("PRC_MANUAL", "accounting-maker", "accounting-checker",
                "corr-resolve", "manual-resolve-key-00001", "CORRECTION_APPLIED",
                "Provider fact verified and correction committed", "INC-901", "ops-resolve").outcome())
                .isEqualTo(ReviewCaseService.Outcome.OK);
        assertThat(jdbc.queryForObject("SELECT case_status FROM payment_review_cases WHERE id='PRC_MANUAL'", String.class))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT requires_manual_review FROM payment_transactions WHERE id='TXN_MANUAL'", Boolean.class))
                .isFalse();
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
