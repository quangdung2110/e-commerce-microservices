package com.platform.inventoryservice.domain.vo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockHashTest {

    @Test
    void construct_validState_success() {
        StockHash h = new StockHash(100L, 20L, 80L, 1000L);
        assertThat(h.total()).isEqualTo(100L);
        assertThat(h.reserved()).isEqualTo(20L);
        assertThat(h.available()).isEqualTo(80L);
    }

    @Test
    void construct_invariantViolation_throwsIllegalArgument() {
        assertThatThrownBy(() -> new StockHash(100L, 20L, 70L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invariant violated");
    }

    @Test
    void construct_negativeTotal_throwsIllegalArgument() {
        assertThatThrownBy(() -> new StockHash(-1L, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void construct_negativeReserved_throwsIllegalArgument() {
        assertThatThrownBy(() -> new StockHash(100L, -1L, 101L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void construct_negativeAvailable_throwsIllegalArgument() {
        assertThatThrownBy(() -> new StockHash(100L, 0L, -1L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void empty_returnsZeroState() {
        StockHash empty = StockHash.empty();
        assertThat(empty.total()).isZero();
        assertThat(empty.reserved()).isZero();
        assertThat(empty.available()).isZero();
    }
}