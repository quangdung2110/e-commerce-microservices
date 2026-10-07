package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.domain.ProviderOperation;

public sealed interface RefundPaymentPreparation {
    record Acquired(ProviderOperation operation, long commandLeaseToken,
                    long operationLeaseToken, long orderId, int paymentAttemptNumber,
                    long amountMinor, String currency, int paymentVersion,
                    long financialStateVersion) implements RefundPaymentPreparation {}
    record Replay(String refundTransactionId, String paymentTransactionId) implements RefundPaymentPreparation {}
    record PaymentNotFound() implements RefundPaymentPreparation {}
    record OrderMismatch() implements RefundPaymentPreparation {}
    record PaymentNotSuccess() implements RefundPaymentPreparation {}
    record AmountExceeded() implements RefundPaymentPreparation {}
    record OrderRefundAlreadyExists() implements RefundPaymentPreparation {}
    record KeyReused() implements RefundPaymentPreparation {}
    record InProgress() implements RefundPaymentPreparation {}
}
