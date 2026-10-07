package com.platform.paymentservice.stripe;

import com.platform.paymentservice.application.port.out.VerifiedWebhook;
import com.platform.paymentservice.application.port.out.WebhookVerificationPort;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeWebhookVerifierTest {
    private static final String OLD_SECRET = "whsec_old_test";
    private static final String CURRENT_SECRET = "whsec_current_test";
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    @Test
    void verifies_exact_payload_with_the_second_rotation_secret() throws Exception {
        byte[] body = payload().getBytes(StandardCharsets.UTF_8);
        String signature = Webhook.Signature.generateSignatureHeader(
                payload(), CURRENT_SECRET, NOW.getEpochSecond());

        VerifiedWebhook verified = new StripeWebhookVerifier(
                List.of(OLD_SECRET, CURRENT_SECRET), Duration.ofMinutes(5))
                .verify(body, signature, NOW);

        assertThat(verified.stripeEventId()).isEqualTo("evt_test_1");
        assertThat(verified.eventType()).isEqualTo("payment_intent.succeeded");
        assertThat(verified.eventCreatedAt()).isEqualTo(NOW.minusSeconds(1));
        assertThat(verified.apiVersion()).isEqualTo("2026-08-26.dahlia");
        assertThat(verified.livemode()).isFalse();
        assertThat(verified.signingSecretId()).startsWith("stripe-webhook-");
        assertThat(verified.signingSecretId()).doesNotContain(CURRENT_SECRET);
    }

    @Test
    void rejects_stale_signature_and_body_changed_after_signing() throws Exception {
        StripeWebhookVerifier verifier = new StripeWebhookVerifier(
                List.of(CURRENT_SECRET), Duration.ofMinutes(5));
        String stale = Webhook.Signature.generateSignatureHeader(
                payload(), CURRENT_SECRET, NOW.minusSeconds(301).getEpochSecond());
        String current = Webhook.Signature.generateSignatureHeader(
                payload(), CURRENT_SECRET, NOW.getEpochSecond());

        assertThatThrownBy(() -> verifier.verify(bytes(payload()), stale, NOW))
                .isInstanceOf(WebhookVerificationPort.InvalidWebhookException.class);
        assertThatThrownBy(() -> verifier.verify(bytes(payload() + " "), current, NOW))
                .isInstanceOf(WebhookVerificationPort.InvalidWebhookException.class);
    }

    @Test
    void rejects_a_signed_malformed_envelope() throws Exception {
        String malformed = "{\"id\":\"evt_test_1\"}";
        String signature = Webhook.Signature.generateSignatureHeader(
                malformed, CURRENT_SECRET, NOW.getEpochSecond());

        assertThatThrownBy(() -> new StripeWebhookVerifier(
                List.of(CURRENT_SECRET), Duration.ofMinutes(5))
                .verify(bytes(malformed), signature, NOW))
                .isInstanceOf(WebhookVerificationPort.InvalidWebhookException.class);
    }

    private static String payload() {
        return """
                {"id":"evt_test_1","object":"event","api_version":"2026-08-26.dahlia","created":1790071199,"data":{"object":{"id":"pi_test_1","object":"payment_intent"}},"livemode":false,"pending_webhooks":1,"request":null,"type":"payment_intent.succeeded"}
                """.strip();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
