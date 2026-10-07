package com.platform.paymentservice.application.port.out;

import java.time.Instant;

public record VerifiedWebhook(
        String stripeEventId,
        String eventType,
        Instant eventCreatedAt,
        String apiVersion,
        boolean livemode,
        String signingSecretId) {
}
