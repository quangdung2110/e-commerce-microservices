package com.platform.paymentservice.domain;

import java.time.Instant;
import java.util.Objects;

public record ProviderOperation(
        String id,
        String paymentTransactionId,
        String refundTransactionId,
        ProviderOperationType type,
        String providerIdempotencyKey,
        String commandIdempotencyKey,
        String requestFingerprintJson,
        ProviderOperationStatus status,
        String providerObjectId,
        int attemptCount,
        Instant firstDispatchedAt,
        Instant safeReplayUntil,
        Instant recoveryDeadlineAt,
        Instant nextRetryAt) {

    public ProviderOperation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(paymentTransactionId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(providerIdempotencyKey);
        Objects.requireNonNull(requestFingerprintJson);
        Objects.requireNonNull(status);
        Objects.requireNonNull(recoveryDeadlineAt);
        if (attemptCount < 0) throw new IllegalArgumentException("attemptCount must be non-negative");
        if ((type == ProviderOperationType.REFUND) != (refundTransactionId != null)) {
            throw new IllegalArgumentException("only REFUND operations have a refundTransactionId");
        }
    }
}
