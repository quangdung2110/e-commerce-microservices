package com.ecommerce.search.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code SEARCH-BR-004} and {@code SEARCH-BR-005}. */
class DiscountCalculatorTest {

    @Test
    void matchesTheWorkedExampleFromTheDomainDoc() {
        // docs/search/domain.md §9: 34.990.000 down to 28.891.500 is 17%.
        assertThat(DiscountCalculator.discountPercentage(34_990_000L, 28_891_500L)).isEqualTo(17);
        assertThat(DiscountCalculator.hasPromotion(34_990_000L, 28_891_500L)).isTrue();
    }

    @Test
    void floorsRatherThanRounds() {
        assertThat(DiscountCalculator.discountPercentage(1000L, 801L)).isEqualTo(19);
    }

    @Test
    void isZeroWhenThereIsNoDiscount() {
        assertThat(DiscountCalculator.discountPercentage(1000L, 1000L)).isZero();
        assertThat(DiscountCalculator.discountPercentage(1000L, 1200L)).isZero();
        assertThat(DiscountCalculator.hasPromotion(1000L, 1000L)).isFalse();
    }

    @Test
    void isZeroRatherThanFailingOnMissingOrZeroPrices() {
        assertThat(DiscountCalculator.discountPercentage(null, 100L)).isZero();
        assertThat(DiscountCalculator.discountPercentage(100L, null)).isZero();
        assertThat(DiscountCalculator.discountPercentage(0L, 0L)).isZero();
        assertThat(DiscountCalculator.hasPromotion(null, 100L)).isFalse();
    }

    @Test
    void reachesOneHundredOnlyWhenTheSellingPriceIsZero() {
        assertThat(DiscountCalculator.discountPercentage(1000L, 0L)).isEqualTo(100);
    }
}
