package com.platform.paymentservice.application.service;

import com.platform.paymentservice.application.port.out.PaymentQueryPort;

import java.util.Objects;
import java.util.Optional;

public final class PaymentQueryService {
    private final PaymentQueryPort queries;

    public PaymentQueryService(PaymentQueryPort queries) {
        this.queries = Objects.requireNonNull(queries);
    }

    public Optional<PaymentQueryPort.PaymentView> payment(String transactionId) {
        return queries.findPayment(transactionId);
    }

    public Optional<PaymentQueryPort.RefundView> refund(String refundTransactionId) {
        return queries.findRefund(refundTransactionId);
    }
}
