package com.ecommerce.search.service;

/**
 * {@code SEARCH-BR-004} and {@code SEARCH-BR-005}.
 *
 * <p>{@code has_promotion} is materialised on the document when a catalog event is
 * applied; {@code discount_percentage} is computed when the response is built, so that a
 * rounding change never requires a reindex.
 */
public final class DiscountCalculator {

    private DiscountCalculator() {
    }

    /** True when the published selling price is below the list price. */
    public static boolean hasPromotion(Long basePriceVnd, Long sellingPriceVnd) {
        return basePriceVnd != null && sellingPriceVnd != null && sellingPriceVnd < basePriceVnd;
    }

    /**
     * {@code floor((base - selling) / base * 100)} when {@code base > 0} and
     * {@code selling < base}; {@code 0} otherwise.
     */
    public static int discountPercentage(Long basePriceVnd, Long sellingPriceVnd) {
        if (basePriceVnd == null || sellingPriceVnd == null || basePriceVnd <= 0 || sellingPriceVnd >= basePriceVnd) {
            return 0;
        }
        long discount = basePriceVnd - sellingPriceVnd;
        return (int) Math.floorDiv(discount * 100L, basePriceVnd);
    }
}
