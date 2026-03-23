package com.ecommerce.promotion.application.exception;

/**
 * Ném khi hồ Flash Sale đã hết vé (Sold Out).
 * Theo thiết kế redis_caching_design.md - Script 1: return -2.
 * Maps to HTTP 410 Gone.
 */
public class FlashSaleSoldOutException extends CoreBusinessException {

    public FlashSaleSoldOutException(Long promotionId) {
        super(
                String.format("Flash sale sold out for promotion: %d", promotionId),
                "FLASH_SALE_SOLD_OUT");
    }
}
