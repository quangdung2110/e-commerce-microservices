package com.ecommerce.promotion.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request body cho API kích hoạt Budget lên Redis.
 * Theo thiết kế redis_caching_design.md - Section 2.2: Warm-up.
 * Admin POST /v1/promotions/{id}/budget/activate
 */
public class ActivateBudgetRequest {

    @NotNull(message = "Total quantity is required")
    @Positive(message = "Total quantity must be positive")
    private Integer totalQuantity;

    @NotNull(message = "Total budget is required")
    @Positive(message = "Total budget must be positive")
    private BigDecimal totalBudget;

    public Integer getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(Integer totalQuantity) { this.totalQuantity = totalQuantity; }

    public BigDecimal getTotalBudget() { return totalBudget; }
    public void setTotalBudget(BigDecimal totalBudget) { this.totalBudget = totalBudget; }
}
