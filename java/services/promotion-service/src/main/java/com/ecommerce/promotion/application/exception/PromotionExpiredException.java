package com.ecommerce.promotion.application.exception;

/**
 * Ném khi Promotion đã hết hạn hoặc không active.
 * Maps to HTTP 410 Gone.
 */
public class PromotionExpiredException extends CoreBusinessException {

    public PromotionExpiredException(Long promotionId) {
        super(
                String.format("Promotion %d is expired or inactive", promotionId),
                "PROMOTION_EXPIRED");
    }

    public PromotionExpiredException(String code) {
        super(
                String.format("Promotion code '%s' is expired or inactive", code),
                "PROMOTION_EXPIRED");
    }
}
