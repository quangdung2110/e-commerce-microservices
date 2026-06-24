package com.ecommerce.search.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DiscountCalculator")
class DiscountCalculatorTest {

    @ParameterizedTest
    @MethodSource("calculations")
    @DisplayName("should calculate discount percentage correctly")
    void calculate(Long original, Long best, int expected) {
        assertThat(DiscountCalculator.calculate(original, best)).isEqualTo(expected);
    }

    static Stream<Arguments> calculations() {
        return Stream.of(
            // Basic cases
            Arguments.of(100L, 80L, 20),      // 20% off
            Arguments.of(1000L, 750L, 25),     // 25% off
            Arguments.of(34_990_000L, 28_891_500L, 17),  // real-world: ~17% off
            // Edge cases
            Arguments.of(100L, 100L, 0),       // same price → 0%
            Arguments.of(100L, 120L, 0),       // best > original → 0%
            Arguments.of(100L, 0L, 100),       // 100% off (giveaway)
            // Null / zero
            Arguments.of(null, 100L, 0),       // null original → 0
            Arguments.of(100L, null, 0),       // null best → 0
            Arguments.of(0L, 0L, 0),           // zero original → 0
            Arguments.of(-100L, 50L, 0)        // negative original → 0
        );
    }
}
