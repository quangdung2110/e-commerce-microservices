package com.platform.paymentservice.application.port.out;

import java.time.Instant;
import java.util.UUID;

public interface VerifiedWebhookPublisherPort {
    void publish(Message message);

    record Message(
            UUID id,
            Instant time,
            VerifiedWebhook webhook,
            Instant receivedAt,
            Instant verifiedAt,
            String rawBodyBase64,
            String rawBodySha256,
            String stripeSignature) {
    }
}
