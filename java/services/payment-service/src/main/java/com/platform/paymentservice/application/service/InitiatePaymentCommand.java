package com.platform.paymentservice.application.service;

import com.platform.paymentservice.domain.Money;

import java.util.Objects;
import java.util.UUID;

public record InitiatePaymentCommand(
        String idempotencyKey,
        long orderId,
        int paymentAttemptNumber,
        Money amount,
        UUID userId,
        String returnUrl,
        String correlationId) {

    public InitiatePaymentCommand {
        if (idempotencyKey == null || idempotencyKey.length() < 16 || idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must contain 16..255 characters");
        }
        if (orderId <= 0 || paymentAttemptNumber < 1) {
            throw new IllegalArgumentException("invalid order payment attempt");
        }
        Objects.requireNonNull(amount);
        Objects.requireNonNull(userId);
        if (returnUrl != null && returnUrl.length() > 2048) {
            throw new IllegalArgumentException("returnUrl is too long");
        }
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 128) {
            throw new IllegalArgumentException("invalid correlationId");
        }
    }
}
