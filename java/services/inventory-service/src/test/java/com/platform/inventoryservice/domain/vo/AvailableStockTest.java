package com.platform.inventoryservice.domain.vo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvailableStockTest {

    @Test
    void construct_validInputs_success() {
        AvailableStock s = new AvailableStock("sku-001", 5L, 100L);
        assertThat(s.skuId()).isEqualTo("sku-001");
        assertThat(s.warehouseId()).isEqualTo(5L);
        assertThat(s.quantity()).isEqualTo(100L);
    }

    @Test
    void construct_blankSkuId_throwsIllegalArgument() {
        assertThatThrownBy(() -> new AvailableStock(null, 5L, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvailableStock("  ", 5L, 100L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void construct_negativeQuantity_throwsIllegalArgument() {
        assertThatThrownBy(() -> new AvailableStock("sku-001", 5L, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canFulfill_sufficient_true() {
        AvailableStock s = new AvailableStock("sku-001", 5L, 100L);
        assertThat(s.canFulfill(50L)).isTrue();
        assertThat(s.canFulfill(100L)).isTrue();
    }

    @Test
    void canFulfill_insufficient_false() {
        AvailableStock s = new AvailableStock("sku-001", 5L, 100L);
        assertThat(s.canFulfill(101L)).isFalse();
    }
}

class CustomerLocationTest {

    @Test
    void construct_validCityCode_success() {
        CustomerLocation loc = new CustomerLocation("HCM", "Q1");
        assertThat(loc.cityCode()).isEqualTo("HCM");
        assertThat(loc.districtCode()).isEqualTo("Q1");
    }

    @Test
    void construct_nullCityCode_throwsIllegalArgument() {
        assertThatThrownBy(() -> new CustomerLocation(null, "Q1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void construct_blankCityCode_throwsIllegalArgument() {
        assertThatThrownBy(() -> new CustomerLocation("  ", "Q1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void construct_nullDistrictCode_allowed() {
        CustomerLocation loc = new CustomerLocation("HCM", null);
        assertThat(loc.districtCode()).isNull();
    }
}