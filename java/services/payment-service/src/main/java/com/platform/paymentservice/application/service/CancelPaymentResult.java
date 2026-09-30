package com.platform.paymentservice.application.service;

import com.platform.paymentservice.domain.Money;

public sealed interface CancelPaymentResult {
    record Canceled(String transactionId, long orderId, long financialStateVersion)
            implements CancelPaymentResult {}
    record AlreadyNonCaptured(String transactionId, long orderId, long financialStateVersion)
            implements CancelPaymentResult {}
    record AlreadySucceeded(String transactionId, long orderId, Money amount,
                            long financialStateVersion) implements CancelPaymentResult {}
    record CancelPending(String transactionId, long orderId, long financialStateVersion)
            implements CancelPaymentResult {}
    record ProviderOutcomeUnknown() implements CancelPaymentResult {}
    record PaymentNotFound() implements CancelPaymentResult {}
    record OrderMismatch() implements CancelPaymentResult {}
    record RequestInProgress() implements CancelPaymentResult {}
    record IdempotencyKeyReused() implements CancelPaymentResult {}
    record Fenced() implements CancelPaymentResult {}
}
