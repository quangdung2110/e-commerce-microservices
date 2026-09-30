package com.platform.paymentservice.application.service;

public sealed interface RefundPaymentResult {
    record Processing(String refundTransactionId, String paymentTransactionId) implements RefundPaymentResult {}
    record PaymentNotFound() implements RefundPaymentResult {}
    record OrderMismatch() implements RefundPaymentResult {}
    record PaymentNotSuccess() implements RefundPaymentResult {}
    record AmountExceeded() implements RefundPaymentResult {}
    record OrderRefundAlreadyExists() implements RefundPaymentResult {}
    record IdempotencyKeyReused() implements RefundPaymentResult {}
    record RequestInProgress() implements RefundPaymentResult {}
    record ProviderOutcomeUnknown() implements RefundPaymentResult {}
    record Fenced() implements RefundPaymentResult {}
}
