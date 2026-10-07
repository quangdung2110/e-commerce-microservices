package com.platform.paymentservice.application.service;

import com.platform.paymentservice.domain.Money;
import com.platform.paymentservice.domain.RefundEvidence;
import com.platform.paymentservice.domain.RefundReason;

import java.util.Objects;

public record RefundPaymentCommand(
        String transactionId,
        long orderId,
        String refundRequestId,
        Money amount,
        RefundReason reason,
        RefundEvidence evidence,
        String idempotencyKey,
        String correlationId) {
    public RefundPaymentCommand {
        if (transactionId == null || transactionId.isBlank())
            throw new IllegalArgumentException("transactionId is required");
        if (orderId <= 0) throw new IllegalArgumentException("orderId must be positive");
        if (refundRequestId == null || refundRequestId.isBlank() || refundRequestId.length() > 128)
            throw new IllegalArgumentException("invalid refundRequestId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.reason() != reason)
            throw new IllegalArgumentException("refund reason does not match inventory evidence");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255)
            throw new IllegalArgumentException("invalid idempotencyKey");
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 255)
            throw new IllegalArgumentException("invalid correlationId");
    }
}
