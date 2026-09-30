package com.platform.paymentservice.application.service;

import java.util.Objects;

public record CancelPaymentCommand(
        String transactionId,
        long orderId,
        Reason reason,
        String idempotencyKey,
        String correlationId) {

    public enum Reason { PAYMENT_TIMEOUT, BUYER_CANCELLED, ORDER_CANCELLED_PRE_CAPTURE }

    public CancelPaymentCommand {
        Objects.requireNonNull(transactionId);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(idempotencyKey);
        Objects.requireNonNull(correlationId);
        if (transactionId.isBlank() || idempotencyKey.isBlank() || correlationId.isBlank())
            throw new IllegalArgumentException("cancel identifiers must not be blank");
        if (orderId <= 0) throw new IllegalArgumentException("orderId must be positive");
    }
}
