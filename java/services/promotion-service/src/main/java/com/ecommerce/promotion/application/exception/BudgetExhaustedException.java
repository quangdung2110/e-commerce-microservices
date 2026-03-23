package com.ecommerce.promotion.application.exception;

/**
 * Ném khi ngân sách (quantity hoặc budget amount) đã cạn kiệt.
 * Theo thiết kế redis_caching_design.md - Script 2: Budget Reservation return 0.
 * Maps to HTTP 409 Conflict.
 */
public class BudgetExhaustedException extends CoreBusinessException {

    private final Long promotionId;

    public BudgetExhaustedException(Long promotionId) {
        super(
                String.format("Budget exhausted for promotion: %d", promotionId),
                "BUDGET_EXHAUSTED");
        this.promotionId = promotionId;
    }

    public Long getPromotionId() {
        return promotionId;
    }
}
