package com.platform.paymentservice.integration;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.EncryptedSecret;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.ProviderCurrentFactPort;
import com.platform.paymentservice.application.port.out.SecretCipherPort;
import com.platform.paymentservice.application.port.out.VerifiedWebhook;
import com.platform.paymentservice.application.port.out.WebhookInboxPort;
import com.platform.paymentservice.application.port.out.WebhookProcessingPort;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookProcessorIT extends AbstractPaymentIT {
    private static final String PAYMENT_ID = "TXN_WEBHOOK_PROCESSOR";
    private static final String EVENT_ID = "evt_processor_1";
    private static final Instant EVENT_CREATED = Instant.parse("2026-09-22T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:01Z");
    private final SecretCipherPort cipher = new TestCipher();
    private final FixedIds ids = new FixedIds();

    @BeforeEach
    void seedPayment() {
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id,order_id,payment_attempt_number,user_id,amount_minor,currency,
                   provider_transaction_id,status,expires_at)
                VALUES (?,99183,1,?,1500000,'VND','pi_processor','PENDING',
                        ?::timestamptz + interval '15 minutes')
                """, PAYMENT_ID, UUID.randomUUID(), NOW.toString());
    }

    @Test
    void succeeded_event_commits_payment_history_outbox_checkpoint_and_inbox_atomically() {
        byte[] body = succeededBody();
        save(body, sha256(body));
        WebhookProcessorService service = service(8);

        WebhookProcessResult result = service.process(notification(body), "worker-a");

        assertThat(result).isEqualTo(WebhookProcessResult.PROCESSED);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id=?", PAYMENT_ID))
                .containsEntry("status", "SUCCESS")
                .containsEntry("financial_state_version", 1L)
                .containsEntry("last_provider_event_id", EVENT_ID)
                .containsEntry("provider_charge_id", "ch_processor");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT partition_key FROM outbox_events", String.class))
                .isEqualTo("99183");
        assertThat(jdbc.queryForObject("SELECT journal_type FROM financial_journals", String.class))
                .isEqualTo("CAPTURE");
        assertThat(jdbc.queryForMap("SELECT * FROM webhook_logs WHERE stripe_event_id=?", EVENT_ID))
                .containsEntry("status", "PROCESSED")
                .containsEntry("payment_transaction_id", PAYMENT_ID)
                .containsEntry("provider_object_id", "pi_processor");
    }

    @Test
    void expired_lease_can_be_taken_over_and_stale_owner_is_fenced() {
        byte[] body = succeededBody();
        save(body, sha256(body));
        var first = (WebhookProcessingPort.Claimed) webhookProcessing.claim(
                EVENT_ID, "worker-a", NOW, Duration.ofSeconds(10));
        jdbc.update("UPDATE webhook_logs SET lease_expires_at=? WHERE stripe_event_id=?",
                java.sql.Timestamp.from(NOW.minusSeconds(1)), EVENT_ID);
        var second = (WebhookProcessingPort.Claimed) webhookProcessing.claim(
                EVENT_ID, "worker-b", NOW, Duration.ofMinutes(1));

        assertThat(second.leaseToken()).isGreaterThan(first.leaseToken());
        assertThat(webhookProcessing.completeIgnored(first, "STALE", NOW,
                "pi_processor", PAYMENT_ID, null, null)).isFalse();
        assertThat(webhookProcessing.completeIgnored(second, "TAKEOVER", NOW,
                "pi_processor", PAYMENT_ID, null, null)).isTrue();
    }

    @Test
    void permanent_integrity_failure_moves_directly_to_dead() {
        byte[] body = succeededBody();
        save(body, "0".repeat(64));
        WebhookProcessorService service = service(2);

        assertThat(service.process(notificationWithHash(body, "0".repeat(64)), "worker-a"))
                .isEqualTo(WebhookProcessResult.DEAD);
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_logs WHERE stripe_event_id=?",
                String.class, EVENT_ID)).isEqualTo("DEAD");
    }

    @Test
    void tied_timestamp_retrieves_current_provider_fact_instead_of_ordering_by_event_id() {
        jdbc.update("""
                UPDATE payment_transactions
                   SET last_provider_event_created_at=?::timestamptz,
                       last_provider_event_id='evt_previous_same_second'
                 WHERE id=?
                """, EVENT_CREATED.toString(), PAYMENT_ID);
        byte[] body = succeededBody();
        save(body, sha256(body));
        AtomicBoolean retrieved = new AtomicBoolean();
        ProviderCurrentFactPort facts = new ProviderCurrentFactPort() {
            @Override public LookupResult retrievePaymentIntent(
                    com.platform.paymentservice.application.port.out.StripeWebhookEvent source,
                    String paymentIntentId) {
                retrieved.set(true);
                return new CurrentFact(source);
            }
            @Override public LookupResult retrieveRefund(
                    com.platform.paymentservice.application.port.out.StripeWebhookEvent source,
                    String providerRefundId) { throw new AssertionError("unexpected refund lookup"); }
            @Override public LookupResult retrieveDispute(
                    com.platform.paymentservice.application.port.out.StripeWebhookEvent source,
                    String providerDisputeId) { throw new AssertionError("unexpected dispute lookup"); }
        };

        assertThat(service(8, facts).process(notification(body), "worker-a"))
                .isEqualTo(WebhookProcessResult.PROCESSED);
        assertThat(retrieved).isTrue();
        assertThat(jdbc.queryForMap("""
                SELECT status,last_provider_event_id FROM payment_transactions WHERE id=?
                """, PAYMENT_ID)).containsEntry("status", "SUCCESS")
                .containsEntry("last_provider_event_id", EVENT_ID);
    }

    @Test
    void tied_timestamp_permanent_retrieve_failure_opens_durable_review_and_advances_checkpoint() {
        jdbc.update("""
                UPDATE payment_transactions
                   SET last_provider_event_created_at=?::timestamptz,
                       last_provider_event_id='evt_previous_same_second'
                 WHERE id=?
                """, EVENT_CREATED.toString(), PAYMENT_ID);
        byte[] body = succeededBody();
        save(body, sha256(body));
        ProviderCurrentFactPort facts = new ProviderCurrentFactPort() {
            @Override public LookupResult retrievePaymentIntent(
                    com.platform.paymentservice.application.port.out.StripeWebhookEvent source,
                    String paymentIntentId) {
                return new ReviewRequired("PROVIDER_RETRIEVE_REJECTED",
                        "[{\"error_code\":\"RESOURCE_MISSING\"}]");
            }
            @Override public LookupResult retrieveRefund(
                    com.platform.paymentservice.application.port.out.StripeWebhookEvent source,
                    String providerRefundId) { throw new AssertionError("unexpected refund lookup"); }
            @Override public LookupResult retrieveDispute(
                    com.platform.paymentservice.application.port.out.StripeWebhookEvent source,
                    String providerDisputeId) { throw new AssertionError("unexpected dispute lookup"); }
        };

        assertThat(service(8, facts).process(notification(body), "worker-a"))
                .isEqualTo(WebhookProcessResult.PROCESSED);
        assertThat(jdbc.queryForMap("""
                SELECT status,requires_manual_review,last_provider_event_id
                  FROM payment_transactions WHERE id=?
                """, PAYMENT_ID)).containsEntry("status", "PENDING")
                .containsEntry("requires_manual_review", true)
                .containsEntry("last_provider_event_id", EVENT_ID);
        assertThat(jdbc.queryForObject("SELECT reason_code FROM payment_review_cases", String.class))
                .isEqualTo("PROVIDER_RETRIEVE_REJECTED");
    }

    private WebhookProcessorService service(int maxAttempts) {
        return service(maxAttempts, null);
    }

    private WebhookProcessorService service(int maxAttempts, ProviderCurrentFactPort currentFacts) {
        return service(maxAttempts, currentFacts, () -> NOW);
    }

    @Test
    void provider_retrieve_that_outlives_inbox_lease_cannot_commit_even_without_takeover() {
        jdbc.update("UPDATE payment_transactions SET last_provider_event_created_at=?::timestamptz, last_provider_event_id='evt_previous' WHERE id=?",
                EVENT_CREATED.toString(), PAYMENT_ID);
        byte[] body = succeededBody();
        save(body, sha256(body));
        var currentTime = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        ProviderCurrentFactPort facts = org.mockito.Mockito.mock(ProviderCurrentFactPort.class);
        org.mockito.Mockito.when(facts.retrievePaymentIntent(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
                    currentTime.set(NOW.plusSeconds(61));
                    return new ProviderCurrentFactPort.CurrentFact(invocation.getArgument(0));
                });
        assertThat(service(8, facts, currentTime::get).process(notification(body), "worker-a"))
                .isEqualTo(WebhookProcessResult.FENCED);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, PAYMENT_ID))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_journals", Integer.class)).isZero();
    }

    private WebhookProcessorService service(int maxAttempts, ProviderCurrentFactPort currentFacts, ClockPort clock) {
        var json = JsonMapper.builder().findAndAddModules().build();
        var handler = new StripeWebhookEventHandler(new StripeWebhookEventParser(json),
                webhookPayments, webhookProcessing, commits, new PaymentStateMachine(), ids, json,
                currentFacts, null, clock);
        return new WebhookProcessorService(webhookProcessing, handler, cipher, ids,
                clock, Duration.ofMinutes(1), Duration.ofSeconds(30), maxAttempts);
    }

    private void save(byte[] body, String hash) {
        String encoded = Base64.getEncoder().encodeToString(body);
        webhookInbox.saveVerified(new WebhookInboxPort.VerifiedDelivery(
                new VerifiedWebhook(EVENT_ID, "payment_intent.succeeded", EVENT_CREATED,
                        "2026-08-26.dahlia", false, "key-1"),
                cipher.encrypt(encoded.toCharArray(), "webhook:" + EVENT_ID + ":raw-body"),
                cipher.encrypt("signature".toCharArray(), "webhook:" + EVENT_ID + ":stripe-signature"),
                hash, NOW, NOW));
    }

    private VerifiedWebhookNotification notification(byte[] body) {
        return notificationWithHash(body, sha256(body));
    }

    private VerifiedWebhookNotification notificationWithHash(byte[] body, String hash) {
        return new VerifiedWebhookNotification(EVENT_ID, "payment_intent.succeeded", EVENT_CREATED,
                Base64.getEncoder().encodeToString(body), hash);
    }

    private static byte[] succeededBody() {
        long epoch = EVENT_CREATED.getEpochSecond();
        return ("""
                {"id":"%s","type":"payment_intent.succeeded","created":%d,
                 "data":{"object":{"id":"pi_processor","object":"payment_intent",
                 "amount":1500000,"currency":"vnd","status":"succeeded",
                 "latest_charge":"ch_processor","metadata":{"payment_transaction_id":"%s"}}}}
                """.formatted(EVENT_ID, epoch, PAYMENT_ID)).getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class TestCipher implements SecretCipherPort {
        @Override
        public EncryptedSecret encrypt(char[] plaintext, String context) {
            return new EncryptedSecret(new String(plaintext).getBytes(StandardCharsets.UTF_8), "test-key");
        }

        @Override
        public char[] decrypt(EncryptedSecret encrypted, String context) {
            return new String(encrypted.ciphertext(), StandardCharsets.UTF_8).toCharArray();
        }
    }

    private static final class FixedIds implements IdGeneratorPort {
        private int sequence;
        @Override public String nextPaymentTransactionId() { return "TXN_UNUSED"; }
        @Override public String nextProviderOperationId() { return "POP_UNUSED"; }
        @Override public String nextReviewCaseId() { return "PRC_WEBHOOK_" + (++sequence); }
        @Override public String nextFinancialJournalId() { return "FJN_WEBHOOK_" + (++sequence); }
        @Override public UUID nextEventId() { return UUID.fromString("00000000-0000-4000-8000-000000000002"); }
    }
}
