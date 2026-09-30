package com.platform.paymentservice.integration;

import com.platform.paymentservice.application.port.out.EncryptedSecret;
import com.platform.paymentservice.application.port.out.VerifiedWebhook;
import com.platform.paymentservice.application.port.out.WebhookInboxPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookIngressIT extends AbstractPaymentIT {
    private static final Instant RECEIVED = Instant.parse("2026-09-22T10:00:00Z");
    private static final VerifiedWebhook WEBHOOK = new VerifiedWebhook(
            "evt_test_1", "payment_intent.succeeded", RECEIVED.minusSeconds(1),
            "2026-08-26.dahlia", false, "stripe-webhook-current");

    @Test
    void non_terminal_redelivery_refreshes_encrypted_delivery_but_terminal_duplicate_is_immutable() {
        WebhookInboxPort.VerifiedDelivery first = delivery(new byte[]{1, 2}, new byte[]{3, 4}, "a".repeat(64));
        assertThat(webhookInbox.saveVerified(first))
                .isEqualTo(WebhookInboxPort.SaveResult.READY_TO_PUBLISH);
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_logs WHERE stripe_event_id=?",
                String.class, WEBHOOK.stripeEventId())).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT raw_body_ciphertext FROM webhook_logs WHERE stripe_event_id=?",
                byte[].class, WEBHOOK.stripeEventId())).containsExactly(1, 2);

        WebhookInboxPort.VerifiedDelivery redelivery = delivery(
                new byte[]{5, 6}, new byte[]{7, 8}, "b".repeat(64));
        assertThat(webhookInbox.saveVerified(redelivery))
                .isEqualTo(WebhookInboxPort.SaveResult.READY_TO_PUBLISH);
        assertThat(jdbc.queryForObject("SELECT raw_body_ciphertext FROM webhook_logs WHERE stripe_event_id=?",
                byte[].class, WEBHOOK.stripeEventId())).containsExactly(5, 6);

        jdbc.update("UPDATE webhook_logs SET status='PROCESSED' WHERE stripe_event_id=?",
                WEBHOOK.stripeEventId());
        WebhookInboxPort.VerifiedDelivery afterTerminal = delivery(
                new byte[]{9}, new byte[]{10}, "c".repeat(64));
        assertThat(webhookInbox.saveVerified(afterTerminal))
                .isEqualTo(WebhookInboxPort.SaveResult.DUPLICATE_TERMINAL);
        assertThat(jdbc.queryForObject("SELECT raw_body_ciphertext FROM webhook_logs WHERE stripe_event_id=?",
                byte[].class, WEBHOOK.stripeEventId())).containsExactly(5, 6);
    }

    private static WebhookInboxPort.VerifiedDelivery delivery(
            byte[] rawCiphertext, byte[] signatureCiphertext, String sha256) {
        return new WebhookInboxPort.VerifiedDelivery(
                WEBHOOK, new EncryptedSecret(rawCiphertext, "key-1"),
                new EncryptedSecret(signatureCiphertext, "key-1"), sha256, RECEIVED, RECEIVED);
    }
}
