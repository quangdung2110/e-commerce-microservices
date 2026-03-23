package com.ecommerce.promotion.domain.enums;

/**
 * Loại hành động Idempotency.
 * Theo thiết kế promotion_db_design.md - Section 1.7
 */
public enum IdempotencyActionType {
    REDEEM_COUPON,
    CANCEL_COUPON,
    REFUND_COUPON
}
