package com.platform.paymentservice.integration;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.ProviderCurrentFactPort;
import com.platform.paymentservice.application.port.out.StripeWebhookEvent;
import com.platform.paymentservice.application.service.ReconciliationService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-022 / RULE-PAY-024: SUCCESS refunds are re-checked for 35 days so a late reversal is found. */
class RefundReconciliationIT extends AbstractPaymentIT {
    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");
    private static final Instant WINDOW_FROM = NOW.minusSeconds(3900);

    @Test
    void reversal_of_a_refund_finalized_twenty_days_ago_is_found_and_booked() {
        seedRefund(1, "SUCCESS", NOW.minus(Duration.ofDays(20)), null);
        RecordingProvider provider = new RecordingProvider(fact("failed", "expired_or_canceled_card",
                "txn_refund_failed"));

        assertThat(service(provider).runObjectState(WINDOW_FROM, NOW, false, 100))
                .isEqualTo(ReconciliationService.Outcome.COMPLETED);

        assertThat(provider.retrieved).containsExactly("re_recon_1");
        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class))
                .isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions", String.class))
                .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT requires_manual_review FROM payment_transactions",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT reason_code FROM payment_review_cases", String.class))
                .isEqualTo("REFUND_REVERSED");
        assertThat(jdbc.queryForObject("SELECT journal_type FROM financial_journals", String.class))
                .isEqualTo("REFUND_REVERSAL");
        assertThat(jdbc.queryForObject("SELECT provider_balance_transaction_id FROM financial_journals",
                String.class)).isEqualTo("txn_refund_failed");
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_events", String.class))
                .isEqualTo("com.ecommerce.payment.refund.reversed.v2");
    }

    @Test
    void refund_finalized_beyond_the_lookback_is_not_asked_of_stripe() {
        seedRefund(1, "SUCCESS", NOW.minus(Duration.ofDays(40)), null);
        RecordingProvider provider = new RecordingProvider(fact("failed", "declined", null));

        assertThat(service(provider).runObjectState(WINDOW_FROM, NOW, false, 100))
                .isEqualTo(ReconciliationService.Outcome.COMPLETED);

        assertThat(provider.retrieved).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class))
                .isEqualTo("SUCCESS");
    }

    @Test
    void requires_action_after_success_is_booked_as_reversal() {
        seedRefund(1, "SUCCESS", NOW.minus(Duration.ofDays(3)), null);
        RecordingProvider provider = new RecordingProvider(fact("requires_action", null, null));

        assertThat(service(provider).runObjectState(WINDOW_FROM, NOW, false, 100))
                .isEqualTo(ReconciliationService.Outcome.COMPLETED);

        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class))
                .isEqualTo("REVERSED");
    }

    @Test
    void requires_action_while_processing_opens_drift_and_keeps_refund_processing() {
        seedRefund(1, "PROCESSING", null, null);
        RecordingProvider provider = new RecordingProvider(fact("requires_action", null, null));

        assertThat(service(provider).runObjectState(WINDOW_FROM, NOW, false, 100))
                .isEqualTo(ReconciliationService.Outcome.COMPLETED);

        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class))
                .isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT reason_code FROM payment_review_cases", String.class))
                .isEqualTo("RECONCILIATION_DRIFT");
    }

    @Test
    void pending_at_stripe_for_a_success_refund_is_drift() {
        seedRefund(1, "SUCCESS", NOW.minus(Duration.ofDays(1)), null);
        RecordingProvider provider = new RecordingProvider(
                new ProviderCurrentFactPort.NoTransition("CURRENT_REFUND_NON_TERMINAL", "pending", "[]"));

        assertThat(service(provider).runObjectState(WINDOW_FROM, NOW, false, 100))
                .isEqualTo(ReconciliationService.Outcome.COMPLETED);

        assertThat(jdbc.queryForObject("SELECT reason_code FROM payment_review_cases", String.class))
                .isEqualTo("RECONCILIATION_DRIFT");
        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class))
                .isEqualTo("SUCCESS");
    }

    @Test
    void newer_refunds_are_not_starved_by_older_ones_when_the_batch_is_full() {
        // 1..3 were checked recently; 4 was never checked and is the newest.
        seedRefund(1, "SUCCESS", NOW.minus(Duration.ofDays(30)), NOW.minusSeconds(600));
        seedRefund(2, "SUCCESS", NOW.minus(Duration.ofDays(29)), NOW.minusSeconds(500));
        seedRefund(3, "SUCCESS", NOW.minus(Duration.ofDays(28)), NOW.minusSeconds(400));
        seedRefund(4, "SUCCESS", NOW.minus(Duration.ofDays(1)), null);
        RecordingProvider provider = new RecordingProvider(fact("succeeded", null, "txn_refund_ok"));

        assertThat(service(provider).runObjectState(WINDOW_FROM, NOW, false, 2))
                .isEqualTo(ReconciliationService.Outcome.COMPLETED);

        // never-synced first, then the least recently synced
        assertThat(provider.retrieved).containsExactly("re_recon_4", "re_recon_1");
    }

    private ReconciliationService service(ProviderCurrentFactPort provider) {
        return new ReconciliationService(reconciliation, provider, commits, new FixedIds(),
                () -> NOW, JsonMapper.builder().findAndAddModules().build(), Duration.ofDays(35));
    }

    /** Stripe's current Refund object, as mapped by the provider adapter. */
    private static ProviderCurrentFactPort.LookupResult fact(String status, String failureReason,
                                                              String balanceTransaction) {
        return new ProviderCurrentFactPort.CurrentFact(new StripeWebhookEvent(
                "reconcile-refund", "succeeded".equals(status) || "requires_action".equals(status)
                ? "refund.updated" : "refund.failed", NOW, "re_x", "pi_x", "ch_x", "re_x", null,
                null, 1500000L, "VND", status, null, null, null, failureReason, null, null,
                balanceTransaction, "{\"status\":\"" + status + "\"}"));
    }

    private void seedRefund(int n, String status, Instant finalizedAt, Instant syncedAt) {
        String payment = "TXN_RECON_" + n;
        // the DB only accepts a refund on a REFUNDING payment; settle the payment afterwards
        String paymentStatus = "REFUNDING";
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id,order_id,payment_attempt_number,user_id,amount_minor,currency,
                   provider_transaction_id,provider_charge_id,status,succeeded_at,
                   financial_state_version,expires_at)
                VALUES (?,?,1,?,1500000,'VND',?,?,?,?::timestamptz,3,?::timestamptz)
                """, payment, 88000 + n, UUID.randomUUID(), "pi_recon_" + n, "ch_recon_" + n,
                paymentStatus, NOW.minus(Duration.ofDays(40)).toString(), NOW.plusSeconds(900).toString());
        jdbc.update("""
                INSERT INTO refund_transactions
                  (id,payment_transaction_id,order_id,refund_request_id,command_idempotency_key,
                   refund_amount_minor,currency,reason,inventory_evidence,provider_refund_id,
                   status,accepted_at,finalized_at,provider_synced_at,updated_at)
                VALUES (?,?,?,?,?,1500000,'VND','CUSTOMER_RETURN_SELECTED_LINES','{}'::jsonb,?,?,
                        ?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz)
                """, "RFD_RECON_" + n, payment, 88000 + n, "req-recon-" + n, "key-recon-" + n,
                "re_recon_" + n, status, NOW.minus(Duration.ofDays(41)).toString(),
                finalizedAt == null ? null : finalizedAt.toString(),
                syncedAt == null ? null : syncedAt.toString(),
                // updated_at far outside the window so only the SUCCESS/PROCESSING rules select it
                NOW.minus(Duration.ofDays(2)).toString());
        if ("SUCCESS".equals(status))
            jdbc.update("UPDATE payment_transactions SET status='REFUNDED' WHERE id=?", payment);
    }

    private static final class RecordingProvider implements ProviderCurrentFactPort {
        private final LookupResult refundResult;
        private final List<String> retrieved = new ArrayList<>();

        private RecordingProvider(LookupResult refundResult) {
            this.refundResult = refundResult;
        }

        @Override public LookupResult retrieveRefund(StripeWebhookEvent source, String id) {
            retrieved.add(id);
            if (refundResult instanceof CurrentFact current) {
                StripeWebhookEvent e = current.event();
                return new CurrentFact(new StripeWebhookEvent(e.eventId(), e.eventType(), e.eventCreatedAt(),
                        id, e.paymentIntentId(), e.providerChargeId(), id, null,
                        e.metadataPaymentTransactionId(), e.amountMinor(), e.currency(),
                        e.providerStatus(), null, null, null, e.failureReason(), null, null,
                        e.providerBalanceTransactionId(), e.sanitizedObjectJson()));
            }
            return refundResult;
        }
        @Override public LookupResult retrievePaymentIntent(StripeWebhookEvent source, String id) {
            return new NoTransition("CURRENT_PAYMENT_NON_FACT", "succeeded", "{}");
        }
        @Override public LookupResult retrieveDispute(StripeWebhookEvent source, String id) {
            throw new AssertionError("no disputes in fixture");
        }
    }

    private static final class FixedIds implements IdGeneratorPort {
        private int sequence;
        @Override public String nextPaymentTransactionId() { return "TXN_UNUSED"; }
        @Override public String nextProviderOperationId() { return "POP_UNUSED"; }
        @Override public String nextReviewCaseId() { return "PRC_RECON_" + (++sequence); }
        @Override public String nextFinancialJournalId() { return "FJN_RECON_" + (++sequence); }
        @Override public UUID nextEventId() {
            return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", ++sequence));
        }
    }
}
