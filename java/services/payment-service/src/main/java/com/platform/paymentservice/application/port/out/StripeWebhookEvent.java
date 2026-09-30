package com.platform.paymentservice.application.port.out;

import java.time.Instant;

public record StripeWebhookEvent(
        String eventId,
        String eventType,
        Instant eventCreatedAt,
        String providerObjectId,
        String paymentIntentId,
        String providerChargeId,
        String providerRefundId,
        String providerDisputeId,
        String metadataPaymentTransactionId,
        Long amountMinor,
        String currency,
        String providerStatus,
        String declineCode,
        String errorMessage,
        String outcomeType,
        String failureReason,
        String reasonCode,
        Instant evidenceDueAt,
        String providerBalanceTransactionId,
        String sanitizedObjectJson) {
}
