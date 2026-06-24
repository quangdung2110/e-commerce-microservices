package com.ecommerce.search.service;

/**
 * Utility for computing the {@code discount_percentage} that appears in
 * product search results.
 *
 * <p>The formula follows the canonical calculation defined in the Search Workflow:
 * {@code floor(((original_price - best_price) / original_price) * 100)}.
 *
 * <p>Returns {@code 0} when the inputs are absent or when
 * {@code best_price >= original_price} (no discount).
 */
public final class DiscountCalculator {

    private DiscountCalculator() {
        // utility class
    }

    /**
     * Calculate the discount percentage.
     *
     * @param originalPrice the original (list) price, may be {@code null}
     * @param bestPrice     the best (effective) price, may be {@code null}
     * @return discount percentage (0-100), or 0 if inputs are invalid
     */
    public static int calculate(Long originalPrice, Long bestPrice) {
        if (originalPrice == null || bestPrice == null || originalPrice <= 0) {
            return 0;
        }
        if (bestPrice >= originalPrice) {
            return 0;
        }
        return (int) Math.floor(((double) (originalPrice - bestPrice) / originalPrice) * 100);
    }
}
