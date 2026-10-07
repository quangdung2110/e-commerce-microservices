package com.platform.paymentservice.integration;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.ProviderCurrentFactPort;
import com.platform.paymentservice.application.port.out.StripeWebhookEvent;
import com.platform.paymentservice.application.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationIT extends AbstractPaymentIT {
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    @BeforeEach
    void seed() {
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id,order_id,payment_attempt_number,user_id,amount_minor,currency,
                   provider_transaction_id,status,expires_at,updated_at)
                VALUES ('TXN_RECON',701,1,?,2500,'VND','pi_recon','PENDING',
                        ?::timestamptz,?::timestamptz)
                """, UUID.randomUUID(), "2026-09-25T11:00:00Z", "2026-09-25T09:30:00Z");
    }

    @Test
    void valid_provider_drift_is_corrected_through_fsm_with_history_outbox_and_journal() {
        ReconciliationService service = service(false);

        assertThat(service.runObjectState(NOW.minusSeconds(3600), NOW, false, 100))
                .isEqualTo(ReconciliationService.Outcome.COMPLETED);

        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id='TXN_RECON'", String.class))
                .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT transition_id FROM payment_status_history WHERE payment_transaction_id='TXN_RECON'", String.class))
                .isEqualTo("CORR");
        assertThat(jdbc.queryForObject("SELECT triggered_by FROM payment_status_history WHERE payment_transaction_id='TXN_RECON'", String.class))
                .isEqualTo("RECONCILIATION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT corrected_count FROM reconciliation_runs", Integer.class)).isEqualTo(1);
    }

    @Test
    void frozen_run_opens_review_without_changing_financial_state() {
        assertThat(service(true).runObjectState(NOW.minusSeconds(3600), NOW, true, 100))
                .isEqualTo(ReconciliationService.Outcome.COMPLETED);

        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id='TXN_RECON'", String.class))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT requires_manual_review FROM payment_transactions WHERE id='TXN_RECON'", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT reason_code FROM payment_review_cases", String.class))
                .isEqualTo("RECONCILIATION_DRIFT");
        assertThat(jdbc.queryForObject("SELECT corrected_count FROM reconciliation_runs", Integer.class)).isZero();
    }

    @Test
    void dispute_lifecycle_drift_is_corrected_through_dispute_fsm() {
        jdbc.update("DELETE FROM payment_transactions");
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id,order_id,payment_attempt_number,user_id,amount_minor,currency,
                   provider_transaction_id,provider_charge_id,status,expires_at,succeeded_at,updated_at)
                VALUES ('TXN_DISPUTE_RECON',702,1,?,2500,'VND','pi_dispute_recon',
                        'ch_dispute_recon','SUCCESS',?::timestamptz,?::timestamptz,?::timestamptz)
                """, UUID.randomUUID(), "2026-09-25T11:00:00Z",
                "2026-09-25T09:00:00Z", "2026-09-25T09:30:00Z");
        jdbc.update("""
                INSERT INTO payment_disputes
                  (id,payment_transaction_id,order_id,provider_dispute_id,amount_minor,currency,
                   reason_code,status,funds_state,opened_at,last_provider_event_created_at,
                   last_provider_event_id,updated_at)
                VALUES ('DSP_RECON','TXN_DISPUTE_RECON',702,'dp_recon',2500,'VND','fraudulent',
                        'UNDER_REVIEW','WITHDRAWN',?::timestamptz,?::timestamptz,'evt_old',?::timestamptz)
                """, "2026-09-24T10:00:00Z", "2026-09-24T10:00:00Z", "2026-09-25T09:30:00Z");

        ReconciliationService service = new ReconciliationService(reconciliation,
                new WonDisputeProvider(), commits, new FixedIds(), () -> NOW,
                JsonMapper.builder().findAndAddModules().build());

        assertThat(service.runObjectState(NOW.minusSeconds(3600), NOW, false, 100))
                .isEqualTo(ReconciliationService.Outcome.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_disputes WHERE id='DSP_RECON'", String.class))
                .isEqualTo("WON");
        assertThat(jdbc.queryForObject("SELECT transition_id FROM payment_status_history WHERE dispute_transaction_id='DSP_RECON'", String.class))
                .isEqualTo("CORR");
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_events", String.class))
                .isEqualTo("com.ecommerce.payment.dispute.won.v2");
    }

    private ReconciliationService service(boolean ignored) {
        return new ReconciliationService(reconciliation, new SucceededProvider(), commits,
                new FixedIds(), () -> NOW, JsonMapper.builder().findAndAddModules().build());
    }

    private static final class SucceededProvider implements ProviderCurrentFactPort {
        @Override
        public LookupResult retrievePaymentIntent(StripeWebhookEvent source, String id) {
            return new CurrentFact(new StripeWebhookEvent(source.eventId(),
                    "payment_intent.succeeded", source.eventCreatedAt(), id, id,
                    "ch_recon", null, null, source.metadataPaymentTransactionId(),
                    2500L, "VND", "succeeded", null, null, null, null,
                    null, null, "txn_recon", "{\"status\":\"succeeded\"}"));
        }
        @Override public LookupResult retrieveRefund(StripeWebhookEvent source, String id) {
            throw new AssertionError("no refunds in fixture");
        }
        @Override public LookupResult retrieveDispute(StripeWebhookEvent source, String id) {
            throw new AssertionError("no disputes in fixture");
        }
    }

    private static final class WonDisputeProvider implements ProviderCurrentFactPort {
        @Override public LookupResult retrievePaymentIntent(StripeWebhookEvent source, String id) {
            return new NoTransition("CURRENT_PAYMENT_NON_FACT", "succeeded", "{}");
        }
        @Override public LookupResult retrieveRefund(StripeWebhookEvent source, String id) {
            throw new AssertionError("no refunds in fixture");
        }
        @Override public LookupResult retrieveDispute(StripeWebhookEvent source, String id) {
            return new CurrentFact(new StripeWebhookEvent(source.eventId(),
                    "charge.dispute.closed", source.eventCreatedAt(), id, null,
                    "ch_dispute_recon", null, id, source.metadataPaymentTransactionId(),
                    2500L, "VND", "won", null, null, null, null,
                    "fraudulent", null, null, "{\"status\":\"won\"}"));
        }
    }

    private static final class FixedIds implements IdGeneratorPort {
        private int event;
        @Override public String nextPaymentTransactionId() { return "TXN_UNUSED"; }
        @Override public String nextProviderOperationId() { return "POP_UNUSED"; }
        @Override public String nextReviewCaseId() { return "PRC_RECON"; }
        @Override public String nextFinancialJournalId() { return "FJN_RECON"; }
        @Override public UUID nextEventId() {
            return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", ++event));
        }
    }
}
