package com.platform.paymentservice.application.service;

import java.time.Instant;

public record VerifiedWebhookNotification(
        String stripeEventId,
        String eventType,
        Instant eventCreatedAt,
        String rawBodyBase64,
        String rawBodySha256) {
}
