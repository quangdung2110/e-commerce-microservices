package com.platform.inventoryservice.domain.vo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkuWarehouseKeyTest {

    @Test
    void construct_validInputs_success() {
        SkuWarehouseKey key = new SkuWarehouseKey("sku-001", 5L);
        assertThat(key.skuId()).isEqualTo("sku-001");
        assertThat(key.warehouseId()).isEqualTo(5L);
    }

    @Test
    void construct_nullSkuId_throwsIllegalArgument() {
        assertThatThrownBy(() -> new SkuWarehouseKey(null, 5L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void construct_blankSkuId_throwsIllegalArgument() {
        assertThatThrownBy(() -> new SkuWarehouseKey("  ", 5L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void construct_zeroWarehouseId_throwsIllegalArgument() {
        assertThatThrownBy(() -> new SkuWarehouseKey("sku-001", 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void construct_negativeWarehouseId_throwsIllegalArgument() {
        assertThatThrownBy(() -> new SkuWarehouseKey("sku-001", -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hashTag_returnsCorrectFormat() {
        SkuWarehouseKey key = new SkuWarehouseKey("sku-001", 5L);
        assertThat(key.hashTag()).isEqualTo("{sku-001:5}");
    }
}