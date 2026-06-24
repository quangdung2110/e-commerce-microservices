package com.platform.inventoryservice.domain.entity;

import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhysicalInventoryTest {

    private static PhysicalInventory fresh(int actual) {
        SkuWarehouseKey key = new SkuWarehouseKey("sku-001", 5L);
        return PhysicalInventory.forUpsert(key, "prod-001", actual);
    }

    @Test
    void forUpsert_setsFields() {
        PhysicalInventory p = fresh(100);
        assertThat(p.getKey().skuId()).isEqualTo("sku-001");
        assertThat(p.getKey().warehouseId()).isEqualTo(5L);
        assertThat(p.getProductId()).isEqualTo("prod-001");
        assertThat(p.getActualQuantity()).isEqualTo(100);
        assertThat(p.getReservedQuantity()).isZero();
    }

    @Test
    void availableQuantity_initial_returnsActual() {
        PhysicalInventory p = fresh(100);
        assertThat(p.availableQuantity()).isEqualTo(100);
    }

    @Test
    void addActualQuantity_positiveDelta_success() {
        PhysicalInventory p = fresh(100);
        p.addActualQuantity(50);
        assertThat(p.getActualQuantity()).isEqualTo(150);
    }

    @Test
    void addActualQuantity_zeroDelta_success() {
        PhysicalInventory p = fresh(100);
        p.addActualQuantity(0);
        assertThat(p.getActualQuantity()).isEqualTo(100);
    }

    @Test
    void addActualQuantity_negative_throwsIllegalArgument() {
        PhysicalInventory p = fresh(100);
        assertThatThrownBy(() -> p.addActualQuantity(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyReserve_sufficientAvailable_success() {
        PhysicalInventory p = fresh(100);
        p.applyReserve(30);
        assertThat(p.getReservedQuantity()).isEqualTo(30);
        assertThat(p.availableQuantity()).isEqualTo(70);
    }

    @Test
    void applyReserve_overflow_throwsIllegalState() {
        PhysicalInventory p = fresh(100);
        p.applyReserve(80);
        assertThatThrownBy(() -> p.applyReserve(21))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot reserve more than available");
    }

    @Test
    void applyReserve_zeroOrNegative_throwsIllegalArgument() {
        PhysicalInventory p = fresh(100);
        assertThatThrownBy(() -> p.applyReserve(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.applyReserve(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyDeduct_sufficientReserved_success() {
        PhysicalInventory p = fresh(100);
        p.applyReserve(50);
        p.applyDeduct(30);
        assertThat(p.getActualQuantity()).isEqualTo(70);
        assertThat(p.getReservedQuantity()).isEqualTo(20);
        assertThat(p.availableQuantity()).isEqualTo(50);
    }

    @Test
    void applyDeduct_insufficientReserved_throwsIllegalState() {
        PhysicalInventory p = fresh(100);
        p.applyReserve(20);
        assertThatThrownBy(() -> p.applyDeduct(21))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient reserved");
    }

    @Test
    void applyDeduct_zeroOrNegative_throwsIllegalArgument() {
        PhysicalInventory p = fresh(100);
        p.applyReserve(20);
        assertThatThrownBy(() -> p.applyDeduct(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.applyDeduct(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyRelease_sufficientReserved_success() {
        PhysicalInventory p = fresh(100);
        p.applyReserve(50);
        p.applyRelease(20);
        assertThat(p.getReservedQuantity()).isEqualTo(30);
        assertThat(p.availableQuantity()).isEqualTo(70);
        assertThat(p.getActualQuantity()).isEqualTo(100);
    }

    @Test
    void applyRelease_underflow_throwsIllegalState() {
        PhysicalInventory p = fresh(100);
        p.applyReserve(20);
        assertThatThrownBy(() -> p.applyRelease(21))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot release more than reserved");
    }

    @Test
    void applyRelease_zeroOrNegative_throwsIllegalArgument() {
        PhysicalInventory p = fresh(100);
        p.applyReserve(20);
        assertThatThrownBy(() -> p.applyRelease(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.applyRelease(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}