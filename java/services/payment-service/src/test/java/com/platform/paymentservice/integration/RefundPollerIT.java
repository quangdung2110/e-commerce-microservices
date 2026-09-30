package com.platform.paymentservice.integration;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.ProviderCurrentFactPort;
import com.platform.paymentservice.application.port.out.StripeWebhookEvent;
import com.platform.paymentservice.application.service.RefundPollerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefundPollerIT extends AbstractPaymentIT {
    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final String PAYMENT_ID = "TXN_REFUND_POLLER";

    @BeforeEach
    void seed() {
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id,order_id,payment_attempt_number,user_id,amount_minor,currency,
                   provider_transaction_id,provider_charge_id,status,succeeded_at,
                   financial_state_version,expires_at)
                VALUES (?,99183,1,?,1500000,'VND','pi_poller','ch_poller','REFUNDING',
                        ?::timestamptz,2,?::timestamptz)
                """, PAYMENT_ID, UUID.randomUUID(), NOW.minusSeconds(600).toString(),
                NOW.plusSeconds(900).toString());
        jdbc.update("""
                INSERT INTO refund_transactions
                  (id,payment_transaction_id,order_id,refund_request_id,command_idempotency_key,
                   refund_amount_minor,currency,reason,inventory_evidence,provider_refund_id,
                   status,accepted_at,updated_at)
                VALUES ('RFD_POLLER',?,99183,'request-poller','refund-key-poller',1500000,'VND',
                        'CUSTOMER_RETURN_SELECTED_LINES','{}'::jsonb,'re_poller','PROCESSING',
                        ?::timestamptz,?::timestamptz)
                """, PAYMENT_ID, NOW.minusSeconds(600).toString(), NOW.minusSeconds(600).toString());
    }

    @Test
    void missed_webhook_is_finalized_from_refund_retrieve_atomically() {
        ProviderCurrentFactPort provider = provider(new ProviderCurrentFactPort.CurrentFact(
                fact("charge.refunded", "succeeded")));
        RefundPollerService service = new RefundPollerService(refundPolling, provider, commits,
                new FixedIds(), (ClockPort) () -> NOW,
                JsonMapper.builder().findAndAddModules().build(), Duration.ofMinutes(1));

        assertThat(service.poll(100)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT status,financial_state_version FROM payment_transactions WHERE id=?",
                PAYMENT_ID)).containsEntry("status", "REFUNDED")
                .containsEntry("financial_state_version", 3L);
        assertThat(jdbc.queryForMap("SELECT status,provider_status_snapshot,provider_synced_at FROM refund_transactions"))
                .containsEntry("status", "SUCCESS").containsEntry("provider_status_snapshot", "succeeded");
        assertThat(jdbc.queryForObject("SELECT journal_type FROM financial_journals", String.class))
                .isEqualTo("REFUND");
        assertThat(jdbc.queryForObject("SELECT partition_key FROM outbox_events", String.class))
                .isEqualTo("99183");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void pending_provider_state_only_records_observation_without_financial_fact() {
        ProviderCurrentFactPort provider = provider(new ProviderCurrentFactPort.NoTransition(
                "CURRENT_REFUND_NON_TERMINAL", "pending", "{}"));
        RefundPollerService service = new RefundPollerService(refundPolling, provider, commits,
                new FixedIds(), (ClockPort) () -> NOW,
                JsonMapper.builder().findAndAddModules().build(), Duration.ofMinutes(1));

        assertThat(service.poll(100)).isZero();
        assertThat(jdbc.queryForMap("SELECT status,provider_status_snapshot,provider_synced_at FROM refund_transactions"))
                .containsEntry("status", "PROCESSING").containsEntry("provider_status_snapshot", "pending");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void canceled_refund_is_finalized_as_failed_with_merchant_request_and_review_case() {
        ProviderCurrentFactPort provider = provider(new ProviderCurrentFactPort.CurrentFact(
                new StripeWebhookEvent("poll:RFD_POLLER", "refund.failed", NOW, "re_poller", "pi_poller",
                        "ch_poller", "re_poller", null, PAYMENT_ID, 1500000L, "VND", "canceled",
                        null, null, null, "merchant_request", null, null, "txn_refund_failed",
                        "{\"status\":\"canceled\"}")));
        RefundPollerService service = new RefundPollerService(refundPolling, provider, commits,
                new FixedIds(), (ClockPort) () -> NOW,
                JsonMapper.builder().findAndAddModules().build(), Duration.ofMinutes(1));

        assertThat(service.poll(100)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT status,provider_failure_reason FROM refund_transactions"))
                .containsEntry("status", "FAILED").containsEntry("provider_failure_reason", "merchant_request");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?",
                String.class, PAYMENT_ID)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT reason_code FROM payment_review_cases", String.class))
                .isEqualTo("REFUND_FAILED_AFTER_ACCEPT");
    }

    @Test
    void requires_action_keeps_refund_processing_and_opens_a_drift_case() {
        ProviderCurrentFactPort provider = provider(new ProviderCurrentFactPort.CurrentFact(
                fact("refund.updated", "requires_action")));
        RefundPollerService service = new RefundPollerService(refundPolling, provider, commits,
                new FixedIds(), (ClockPort) () -> NOW,
                JsonMapper.builder().findAndAddModules().build(), Duration.ofMinutes(1));

        assertThat(service.poll(100)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class))
                .isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT reason_code FROM payment_review_cases", String.class))
                .isEqualTo("RECONCILIATION_DRIFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    private static ProviderCurrentFactPort provider(ProviderCurrentFactPort.LookupResult result) {
        return new ProviderCurrentFactPort() {
            @Override public LookupResult retrieveRefund(StripeWebhookEvent source, String id) { return result; }
            @Override public LookupResult retrievePaymentIntent(StripeWebhookEvent source, String id) {
                throw new AssertionError("unexpected payment lookup");
            }
            @Override public LookupResult retrieveDispute(StripeWebhookEvent source, String id) {
                throw new AssertionError("unexpected dispute lookup");
            }
        };
    }

    private static StripeWebhookEvent fact(String type, String status) {
        return new StripeWebhookEvent("poll:RFD_POLLER", type, NOW, "re_poller", "pi_poller",
                "ch_poller", "re_poller", null, PAYMENT_ID, 1500000L, "VND", status,
                null, null, null, null, null, null, null, "{\"status\":\"" + status + "\"}");
    }

    private static final class FixedIds implements IdGeneratorPort {
        private int sequence;
        @Override public String nextPaymentTransactionId() { return "TXN_UNUSED"; }
        @Override public String nextProviderOperationId() { return "POP_UNUSED"; }
        @Override public String nextReviewCaseId() { return "PRC_POLLER_" + (++sequence); }
        @Override public String nextFinancialJournalId() { return "FJN_POLLER_" + (++sequence); }
        @Override public UUID nextEventId() {
            return new UUID(0x0000000000004000L, 0x8000000000000000L + (++sequence));
        }
    }
}
