package com.ecommerce.order.domain;

/** Denormalized payment read status (domain.md §5.3). */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    REFUNDING,
    REFUNDED,
    REFUND_FAILED
}
