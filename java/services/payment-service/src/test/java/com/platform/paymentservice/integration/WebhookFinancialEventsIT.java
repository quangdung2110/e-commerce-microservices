package com.platform.paymentservice.integration;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.EncryptedSecret;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.ProviderCurrentFactPort;
import com.platform.paymentservice.application.port.out.SecretCipherPort;
import com.platform.paymentservice.application.port.out.StripeWebhookEvent;
import com.platform.paymentservice.application.port.out.VerifiedWebhook;
import com.platform.paymentservice.application.port.out.WebhookInboxPort;
import com.platform.paymentservice.application.service.StripeWebhookEventHandler;
import com.platform.paymentservice.application.service.VerifiedWebhookNotification;
import com.platform.paymentservice.application.service.WebhookProcessResult;
import com.platform.paymentservice.application.service.WebhookProcessorService;
import com.platform.paymentservice.domain.PaymentStateMachine;
import com.platform.paymentservice.stripe.StripeWebhookEventParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookFinancialEventsIT extends AbstractPaymentIT {
    private static final String PAYMENT_ID = "TXN_FINANCIAL_WEBHOOK";
    private static final Instant NOW = Instant.parse("2026-09-23T05:00:00Z");
    private final SecretCipherPort cipher = new TestCipher();
    private final FixedIds ids = new FixedIds();

    @BeforeEach
    void seedPayment() {
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id,order_id,payment_attempt_number,user_id,amount_minor,currency,
                   provider_transaction_id,provider_charge_id,status,financial_state_version,
                   expires_at,succeeded_at)
                VALUES (?,99183,1,?,1500000,'VND','pi_financial','ch_financial',
                        'SUCCESS',1,?::timestamptz,?::timestamptz)
                """, PAYMENT_ID, UUID.randomUUID(), NOW.plusSeconds(900).toString(),
                NOW.minusSeconds(300).toString());
    }

    @Test
    void charge_refunded_atomically_finalizes_refund_payment_journal_outbox_and_inbox() {
        jdbc.update("UPDATE payment_transactions SET status='REFUNDING',financial_state_version=2 WHERE id=?",
                PAYMENT_ID);
        insertRefund("PROCESSING", "2026-09-23T04:00:00Z", "evt_refund_processing");
        String eventId = "evt_refund_success";
        Instant created = NOW.minusSeconds(1);
        byte[] body = ("""
                {"id":"%s","type":"charge.refunded","created":%d,
                 "data":{"object":{"id":"ch_financial","object":"charge",
                 "amount":1500000,"amount_refunded":1500000,"currency":"vnd",
                 "payment_intent":"pi_financial","refunded":true}}}
                """.formatted(eventId, created.getEpochSecond())).getBytes(StandardCharsets.UTF_8);
        save(eventId, "charge.refunded", created, body);

        assertThat(service(retrieved("succeeded", "txn_refund_ok", null))
                .process(notification(eventId, "charge.refunded", created, body), "worker-a"))
                .withFailMessage(() -> "processing_error=" + jdbc.queryForObject(
                        "SELECT processing_error FROM webhook_logs WHERE stripe_event_id=?",
                        String.class, eventId))
                .isEqualTo(WebhookProcessResult.PROCESSED);
        assertThat(jdbc.queryForMap("SELECT status,financial_state_version FROM payment_transactions WHERE id=?",
                PAYMENT_ID)).containsEntry("status", "REFUNDED").containsEntry("financial_state_version", 3L);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class))
                .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT journal_type FROM financial_journals", String.class))
                .isEqualTo("REFUND");
        // ADR-022: the refund's own balance transaction, never the charge's capture one.
        assertThat(jdbc.queryForObject("SELECT provider_balance_transaction_id FROM financial_journals",
                String.class)).isEqualTo("txn_refund_ok");
        assertThat(jdbc.queryForObject("SELECT partition_key FROM outbox_events", String.class))
                .isEqualTo("99183");
        assertThat(jdbc.queryForMap("SELECT status,refund_transaction_id FROM webhook_logs WHERE stripe_event_id=?",
                eventId)).containsEntry("status", "PROCESSED").containsEntry("refund_transaction_id", "RFD_FINANCIAL");
    }

    @Test
    void failed_update_after_success_records_reversal_and_review_without_second_refund() {
        insertRefund("SUCCESS", "2026-09-23T04:00:00Z", "evt_refund_success_old");
        jdbc.update("UPDATE payment_transactions SET status='REFUNDED',financial_state_version=3 WHERE id=?",
                PAYMENT_ID);
        String eventId = "evt_refund_reversed";
        Instant created = NOW.minusSeconds(1);
        byte[] body = ("""
                {"id":"%s","type":"refund.failed","created":%d,
                 "data":{"object":{"id":"re_financial","object":"refund",
                 "amount":1500000,"currency":"vnd","status":"failed",
                 "failure_reason":"expired_or_canceled_card","charge":"ch_financial",
                 "balance_transaction":"txn_refund_original",
                 "failure_balance_transaction":"txn_refund_failed",
                 "payment_intent":"pi_financial"}}}
                """.formatted(eventId, created.getEpochSecond())).getBytes(StandardCharsets.UTF_8);
        save(eventId, "refund.failed", created, body);

        assertThat(service().process(notification(eventId, "refund.failed", created, body), "worker-a"))
                .isEqualTo(WebhookProcessResult.PROCESSED);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?",
                String.class, PAYMENT_ID)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class))
                .isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_transactions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reason_code FROM payment_review_cases", String.class))
                .isEqualTo("REFUND_REVERSED");
        assertThat(jdbc.queryForObject("SELECT journal_type FROM financial_journals", String.class))
                .isEqualTo("REFUND_REVERSAL");
        // ADR-022: money Stripe gave back is booked by failure_balance_transaction.
        assertThat(jdbc.queryForObject("SELECT provider_balance_transaction_id FROM financial_journals",
                String.class)).isEqualTo("txn_refund_failed");
    }

    @Test
    void charge_refunded_while_refund_is_still_pending_does_not_finalize() {
        jdbc.update("UPDATE payment_transactions SET status='REFUNDING',financial_state_version=2 WHERE id=?",
                PAYMENT_ID);
        insertRefund("PROCESSING", "2026-09-23T04:00:00Z", "evt_refund_processing");
        String eventId = "evt_charge_refunded_pending";
        Instant created = NOW.minusSeconds(1);
        byte[] body = chargeRefundedBody(eventId, created);
        save(eventId, "charge.refunded", created, body);

        ProviderCurrentFactPort pending = provider(new ProviderCurrentFactPort.NoTransition(
                "CURRENT_REFUND_NON_TERMINAL", "pending", "{}"));
        assertThat(service(pending).process(notification(eventId, "charge.refunded", created, body),
                "worker-a")).isEqualTo(WebhookProcessResult.PROCESSED);

        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class))
                .isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?",
                String.class, PAYMENT_ID)).isEqualTo("REFUNDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void refund_updated_succeeded_finalizes_refund() {
        jdbc.update("UPDATE payment_transactions SET status='REFUNDING',financial_state_version=2 WHERE id=?",
                PAYMENT_ID);
        insertRefund("PROCESSING", "2026-09-23T04:00:00Z", "evt_refund_processing");
        byte[] body = refundBody("evt_refund_updated_ok", "refund.updated", "succeeded",
                "\"balance_transaction\":\"txn_refund_ok\"");
        save("evt_refund_updated_ok", "refund.updated", NOW.minusSeconds(1), body);

        assertThat(service().process(notification("evt_refund_updated_ok", "refund.updated",
                NOW.minusSeconds(1), body), "worker-a")).isEqualTo(WebhookProcessResult.PROCESSED);

        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class))
                .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?",
                String.class, PAYMENT_ID)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT provider_balance_transaction_id FROM financial_journals",
                String.class)).isEqualTo("txn_refund_ok");
    }

    @Test
    void refund_updated_pending_is_ignored_without_financial_fact() {
        jdbc.update("UPDATE payment_transactions SET status='REFUNDING',financial_state_version=2 WHERE id=?",
                PAYMENT_ID);
        insertRefund("PROCESSING", "2026-09-23T04:00:00Z", "evt_refund_processing");
        byte[] body = refundBody("evt_refund_updated_pending", "refund.updated", "pending", "");
        save("evt_refund_updated_pending", "refund.updated", NOW.minusSeconds(1), body);

        assertThat(service().process(notification("evt_refund_updated_pending", "refund.updated",
                NOW.minusSeconds(1), body), "worker-a")).isEqualTo(WebhookProcessResult.PROCESSED);

        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class))
                .isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void canceled_refund_while_processing_fails_with_merchant_request_and_opens_review() {
        jdbc.update("UPDATE payment_transactions SET status='REFUNDING',financial_state_version=2 WHERE id=?",
                PAYMENT_ID);
        insertRefund("PROCESSING", "2026-09-23T04:00:00Z", "evt_refund_processing");
        byte[] body = refundBody("evt_refund_canceled", "refund.updated", "canceled",
                "\"failure_balance_transaction\":\"txn_refund_failed\"");
        save("evt_refund_canceled", "refund.updated", NOW.minusSeconds(1), body);

        assertThat(service().process(notification("evt_refund_canceled", "refund.updated",
                NOW.minusSeconds(1), body), "worker-a")).isEqualTo(WebhookProcessResult.PROCESSED);

        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT provider_failure_reason FROM refund_transactions",
                String.class)).isEqualTo("merchant_request");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?",
                String.class, PAYMENT_ID)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT reason_code FROM payment_review_cases", String.class))
                .isEqualTo("REFUND_FAILED_AFTER_ACCEPT");
        assertThat(jdbc.queryForObject("SELECT payload::text FROM outbox_events", String.class))
                .contains("MERCHANT_REQUEST");
    }

    @Test
    void requires_action_after_success_is_a_reversal() {
        insertRefund("SUCCESS", "2026-09-23T04:00:00Z", "evt_refund_success_old");
        jdbc.update("UPDATE payment_transactions SET status='REFUNDED',financial_state_version=3 WHERE id=?",
                PAYMENT_ID);
        byte[] body = refundBody("evt_refund_action", "refund.updated", "requires_action", "");
        save("evt_refund_action", "refund.updated", NOW.minusSeconds(1), body);

        assertThat(service().process(notification("evt_refund_action", "refund.updated",
                NOW.minusSeconds(1), body), "worker-a")).isEqualTo(WebhookProcessResult.PROCESSED);

        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class))
                .isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("SELECT reason_code FROM payment_review_cases", String.class))
                .isEqualTo("REFUND_REVERSED");
    }

    @Test
    void requires_action_while_processing_opens_drift_case_without_finalizing() {
        jdbc.update("UPDATE payment_transactions SET status='REFUNDING',financial_state_version=2 WHERE id=?",
                PAYMENT_ID);
        insertRefund("PROCESSING", "2026-09-23T04:00:00Z", "evt_refund_processing");
        byte[] body = refundBody("evt_refund_action_early", "refund.updated", "requires_action", "");
        save("evt_refund_action_early", "refund.updated", NOW.minusSeconds(1), body);

        assertThat(service().process(notification("evt_refund_action_early", "refund.updated",
                NOW.minusSeconds(1), body), "worker-a")).isEqualTo(WebhookProcessResult.PROCESSED);

        assertThat(jdbc.queryForObject("SELECT status FROM refund_transactions", String.class))
                .isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT reason_code FROM payment_review_cases", String.class))
                .isEqualTo("RECONCILIATION_DRIFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void dispute_on_payment_with_live_refund_flags_refund_overlap() {
        insertRefund("SUCCESS", "2026-09-23T04:00:00Z", "evt_refund_success_old");
        jdbc.update("UPDATE payment_transactions SET status='REFUNDED',financial_state_version=3 WHERE id=?",
                PAYMENT_ID);
        String id = "evt_dispute_overlap";
        Instant at = NOW.minusSeconds(1);
        byte[] body = disputeBody(id, "charge.dispute.created", at, "needs_response", true);
        save(id, "charge.dispute.created", at, body);

        assertThat(service().process(notification(id, "charge.dispute.created", at, body), "worker-a"))
                .isEqualTo(WebhookProcessResult.PROCESSED);

        assertThat(jdbc.queryForObject("SELECT evidence::text FROM payment_review_cases", String.class))
                .containsPattern("\"refund_overlap\":\\s*true");
    }

    @Test
    void dispute_without_refund_has_no_refund_overlap_flag() {
        String id = "evt_dispute_no_overlap";
        Instant at = NOW.minusSeconds(1);
        byte[] body = disputeBody(id, "charge.dispute.created", at, "needs_response", true);
        save(id, "charge.dispute.created", at, body);

        assertThat(service().process(notification(id, "charge.dispute.created", at, body), "worker-a"))
                .isEqualTo(WebhookProcessResult.PROCESSED);

        assertThat(jdbc.queryForObject("SELECT evidence::text FROM payment_review_cases", String.class))
                .doesNotContain("refund_overlap");
    }

    @Test
    void dispute_created_then_funds_withdrawn_uses_orthogonal_fsm_and_balanced_journal() {
        String createdId = "evt_dispute_created";
        Instant createdAt = NOW.minusSeconds(2);
        byte[] createdBody = disputeBody(createdId, "charge.dispute.created", createdAt,
                "needs_response", true);
        save(createdId, "charge.dispute.created", createdAt, createdBody);
        assertThat(service().process(notification(createdId, "charge.dispute.created", createdAt,
                createdBody), "worker-a")).isEqualTo(WebhookProcessResult.PROCESSED);

        assertThat(jdbc.queryForMap("SELECT status,funds_state FROM payment_disputes"))
                .containsEntry("status", "OPEN").containsEntry("funds_state", "UNCHANGED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?",
                String.class, PAYMENT_ID)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT reason_code FROM payment_review_cases", String.class))
                .isEqualTo("DISPUTE_OPENED");

        String fundsId = "evt_dispute_funds_withdrawn";
        Instant fundsAt = NOW.minusSeconds(1);
        byte[] fundsBody = disputeBody(fundsId, "charge.dispute.funds_withdrawn", fundsAt,
                "needs_response", false);
        save(fundsId, "charge.dispute.funds_withdrawn", fundsAt, fundsBody);
        assertThat(service().process(notification(fundsId, "charge.dispute.funds_withdrawn", fundsAt,
                fundsBody), "worker-b")).isEqualTo(WebhookProcessResult.PROCESSED);

        assertThat(jdbc.queryForObject("SELECT funds_state FROM payment_disputes", String.class))
                .isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("SELECT financial_state_version FROM payment_transactions WHERE id=?",
                Long.class, PAYMENT_ID)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT journal_type FROM financial_journals", String.class))
                .isEqualTo("DISPUTE_WITHDRAWAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_ledger_entries", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(2);
    }

    private void insertRefund(String status, String checkpointAt, String checkpointId) {
        jdbc.update("""
                INSERT INTO refund_transactions
                  (id,payment_transaction_id,order_id,refund_request_id,command_idempotency_key,
                   refund_amount_minor,currency,reason,inventory_evidence,provider_refund_id,
                   status,last_provider_event_created_at,last_provider_event_id)
                VALUES ('RFD_FINANCIAL',?,99183,'request-1','refund-key-1',1500000,'VND',
                        'CUSTOMER_RETURN_SELECTED_LINES','{}'::jsonb,'re_financial',?,
                        ?::timestamptz,?)
                """, PAYMENT_ID, status, checkpointAt, checkpointId);
    }

    private WebhookProcessorService service() {
        return service(null);
    }

    private WebhookProcessorService service(ProviderCurrentFactPort currentFacts) {
        var json = JsonMapper.builder().findAndAddModules().build();
        var handler = new StripeWebhookEventHandler(new StripeWebhookEventParser(json),
                webhookPayments, webhookProcessing, commits, new PaymentStateMachine(), ids, json,
                currentFacts, null);
        return new WebhookProcessorService(webhookProcessing, handler, cipher, ids,
                (ClockPort) () -> NOW, Duration.ofMinutes(1), Duration.ofSeconds(30), 8);
    }

    private static byte[] chargeRefundedBody(String eventId, Instant created) {
        return ("""
                {"id":"%s","type":"charge.refunded","created":%d,
                 "data":{"object":{"id":"ch_financial","object":"charge",
                 "amount":1500000,"amount_refunded":1500000,"currency":"vnd",
                 "balance_transaction":"txn_capture",
                 "payment_intent":"pi_financial","refunded":true}}}
                """.formatted(eventId, created.getEpochSecond())).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] refundBody(String eventId, String type, String status, String extra) {
        return ("""
                {"id":"%s","type":"%s","created":%d,
                 "data":{"object":{"id":"re_financial","object":"refund",
                 "amount":1500000,"currency":"vnd","status":"%s","charge":"ch_financial",
                 "payment_intent":"pi_financial"%s}}}
                """.formatted(eventId, type, NOW.minusSeconds(1).getEpochSecond(), status,
                extra.isEmpty() ? "" : "," + extra)).getBytes(StandardCharsets.UTF_8);
    }

    /** Refund object as Stripe returns it on retrieve, already mapped by the provider adapter. */
    private static ProviderCurrentFactPort retrieved(String status, String balanceTransaction,
                                                     String failureReason) {
        return provider(new ProviderCurrentFactPort.CurrentFact(new StripeWebhookEvent(
                "evt_refund_success", "refund.updated", NOW.minusSeconds(1), "re_financial",
                "pi_financial", "ch_financial", "re_financial", null, PAYMENT_ID, 1500000L, "VND",
                status, null, null, null, failureReason, null, null, balanceTransaction,
                "{\"status\":\"" + status + "\"}")));
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

    private void save(String eventId, String eventType, Instant created, byte[] body) {
        String encoded = Base64.getEncoder().encodeToString(body);
        webhookInbox.saveVerified(new WebhookInboxPort.VerifiedDelivery(
                new VerifiedWebhook(eventId, eventType, created, "2026-08-26.dahlia", false, "key-1"),
                cipher.encrypt(encoded.toCharArray(), "webhook:" + eventId + ":raw-body"),
                cipher.encrypt("signature".toCharArray(), "webhook:" + eventId + ":stripe-signature"),
                sha256(body), NOW, NOW));
    }

    private static VerifiedWebhookNotification notification(
            String eventId, String eventType, Instant created, byte[] body) {
        return new VerifiedWebhookNotification(eventId, eventType, created,
                Base64.getEncoder().encodeToString(body), sha256(body));
    }

    private static byte[] disputeBody(String eventId, String type, Instant created,
                                      String status, boolean withEvidence) {
        String evidence = withEvidence
                ? ",\"evidence_details\":{\"due_by\":" + created.plusSeconds(86400).getEpochSecond() + "}"
                : "";
        return ("""
                {"id":"%s","type":"%s","created":%d,
                 "data":{"object":{"id":"dp_financial","object":"dispute",
                 "amount":1500000,"currency":"vnd","status":"%s",
                 "reason":"fraudulent","charge":"ch_financial"%s}}}
                """.formatted(eventId, type, created.getEpochSecond(), status, evidence))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class TestCipher implements SecretCipherPort {
        @Override public EncryptedSecret encrypt(char[] plaintext, String context) {
            return new EncryptedSecret(new String(plaintext).getBytes(StandardCharsets.UTF_8), "test-key");
        }
        @Override public char[] decrypt(EncryptedSecret encrypted, String context) {
            return new String(encrypted.ciphertext(), StandardCharsets.UTF_8).toCharArray();
        }
    }

    private static final class FixedIds implements IdGeneratorPort {
        private int sequence;
        @Override public String nextPaymentTransactionId() { return "TXN_UNUSED"; }
        @Override public String nextProviderOperationId() { return "POP_UNUSED"; }
        @Override public String nextReviewCaseId() { return "PRC_FIN_" + (++sequence); }
        @Override public String nextDisputeTransactionId() { return "DSP_FINANCIAL"; }
        @Override public String nextFinancialJournalId() { return "FJN_FIN_" + (++sequence); }
        @Override public UUID nextEventId() {
            return new UUID(0x0000000000004000L, 0x8000000000000000L + (++sequence));
        }
    }
}
