package com.platform.paymentservice.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read-only view of payment, refund and dispute state for the Order workload (OpenAPI getPayment/getRefund). */
public interface PaymentQueryPort {
    Optional<PaymentView> findPayment(String transactionId);

    Optional<RefundView> findRefund(String refundTransactionId);

    record PaymentView(String transactionId, long orderId, int paymentAttemptNumber, long amountMinor,
                       String currency, String status, long financialStateVersion,
                       boolean requiresManualReview, Instant succeededAt, Instant terminalAt,
                       RefundView refund, List<DisputeView> disputes) {
        public PaymentView { disputes = List.copyOf(disputes); }
    }

    record RefundView(String refundTransactionId, String paymentTransactionId, long orderId,
                      long amountMinor, String currency, String status) {}

    record DisputeView(String disputeTransactionId, String paymentTransactionId, long orderId,
                       long amountMinor, String currency, String status, String fundsState,
                       String reasonCode, Instant evidenceDueAt, Instant openedAt, Instant closedAt) {}
}
