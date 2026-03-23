package com.ecommerce.promotion.application.exception;

/**
 * Ném khi User đã claim Flash Sale mã này rồi.
 * Theo thiết kế redis_caching_design.md - Script 1: return -1.
 * Maps to HTTP 409 Conflict.
 */
public class DuplicateClaimException extends CoreBusinessException {

    public DuplicateClaimException(Long promotionId, Long userId) {
        super(
                String.format("User %d already claimed promotion %d", userId, promotionId),
                "DUPLICATE_CLAIM");
    }
}
