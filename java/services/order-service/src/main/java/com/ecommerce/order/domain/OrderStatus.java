package com.ecommerce.order.domain;

import java.util.EnumSet;
import java.util.Set;

/** Master Order FSM states (domain.md §5.1). CREATED is transaction-local only. */
public enum OrderStatus {
    CREATED,
    PENDING_PAYMENT,
    CANCELLING,
    PAID,
    PROCESSING,
    COMPLETED,
    CANCELLED,
    RETURN_REQUESTED,
    REFUNDED,
    FINANCIAL_RECONCILIATION_REQUIRED;

    /** States that have crossed the Payment financial watermark (ADR-011). */
    public static final Set<OrderStatus> CAPTURED = EnumSet.of(PAID, PROCESSING, COMPLETED, RETURN_REQUESTED,
            REFUNDED, FINANCIAL_RECONCILIATION_REQUIRED);

    public boolean isPrePayment() {
        return this == PENDING_PAYMENT || this == CANCELLING;
    }
}
