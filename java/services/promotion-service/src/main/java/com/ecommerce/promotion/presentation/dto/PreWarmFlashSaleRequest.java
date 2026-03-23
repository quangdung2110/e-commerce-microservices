package com.ecommerce.promotion.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request body cho API Pre-warm Flash Sale.
 * Theo thiết kế promotion_workflow.md - Luồng 2 Bước 1:
 *   Admin insert coupon_claims + push vào Redis Set.
 */
public class PreWarmFlashSaleRequest {

    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be positive")
    private Integer quantity;

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
}
