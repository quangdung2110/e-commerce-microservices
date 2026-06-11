package com.platform.productservice.domain.service;

import com.platform.productservice.domain.enums.DiscountType;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure domain service for markdown price calculation.
 * Contains no infrastructure dependencies.
 */
@DomainService
public class MarkdownCalculator {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    /**
     * Calculate the promotional/markdown price based on discount parameters.
     *
     * @param originalPrice the original product price
     * @param discountType the type of discount (PERCENTAGE or FIXED_AMOUNT)
     * @param discountValue the discount value (percentage or fixed amount)
     * @param maxDiscountAmount optional maximum discount cap
     * @return the calculated promotional price, rounded to 2 decimal places
     */
    public BigDecimal calculate(BigDecimal originalPrice, DiscountType discountType,
                                BigDecimal discountValue, BigDecimal maxDiscountAmount) {
        if (originalPrice == null || originalPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Original price must be a positive number");
        }
        if (discountValue == null || discountValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Discount value must be a positive number");
        }
        if (discountType == null) {
            throw new IllegalArgumentException("Discount type must not be null");
        }

        BigDecimal discountAmount;

        if (discountType == DiscountType.PERCENTAGE) {
            discountAmount = originalPrice
                    .multiply(discountValue)
                    .divide(BigDecimal.valueOf(100), SCALE, ROUNDING_MODE);

            if (maxDiscountAmount != null && maxDiscountAmount.compareTo(BigDecimal.ZERO) > 0) {
                discountAmount = discountAmount.min(maxDiscountAmount);
            }
        } else {
            discountAmount = discountValue;
        }

        BigDecimal promotionalPrice = originalPrice.subtract(discountAmount);
        return promotionalPrice.max(BigDecimal.ZERO).setScale(SCALE, ROUNDING_MODE);
    }
}
