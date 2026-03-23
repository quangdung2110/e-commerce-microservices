package com.ecommerce.promotion.domain.enums;

/**
 * Trạng thái Outbox Event.
 * Theo thiết kế promotion_db_design.md - Section 1.8
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED,
    DEAD
}
