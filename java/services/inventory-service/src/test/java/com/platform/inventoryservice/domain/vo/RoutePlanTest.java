package com.platform.inventoryservice.domain.vo;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutePlanTest {

    private static RoutePlan.RoutePlanItem item(String skuId, String productId, long qty, long warehouseId) {
        return new RoutePlan.RoutePlanItem(skuId, productId, qty, warehouseId);
    }

    private static RoutePlan.WarehouseAssignment assignment(long warehouseId, long qty) {
        return new RoutePlan.WarehouseAssignment(warehouseId, qty);
    }

    @Test
    void construct_validInputs_success() {
        UUID id = UUID.randomUUID();
        Instant exp = Instant.now().plusSeconds(1200);
        RoutePlan plan = new RoutePlan(id, exp,
                List.of(item("sku-001", "prod-001", 2L, 5L)),
                List.of(assignment(5L, 50L)));

        assertThat(plan.routePlanId()).isEqualTo(id);
        assertThat(plan.expiresAt()).isEqualTo(exp);
        assertThat(plan.items()).hasSize(1);
        assertThat(plan.warehouseAssignments()).hasSize(1);
    }

    @Test
    void construct_itemsImmutable() {
        UUID id = UUID.randomUUID();
        Instant exp = Instant.now().plusSeconds(1200);
        List<RoutePlan.RoutePlanItem> src = new java.util.ArrayList<>(
                List.of(item("sku-001", "prod-001", 2L, 5L)));
        RoutePlan plan = new RoutePlan(id, exp, src, List.of(assignment(5L, 50L)));

        assertThatThrownBy(() -> plan.items().add(item("sku-002", "prod-002", 1L, 5L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void construct_nullRoutePlanId_throwsIllegalArgument() {
        assertThatThrownBy(() -> new RoutePlan(null, Instant.now(),
                List.of(item("sku-001", "prod-001", 2L, 5L)),
                List.of(assignment(5L, 50L))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void construct_nullExpiresAt_throwsIllegalArgument() {
        assertThatThrownBy(() -> new RoutePlan(UUID.randomUUID(), null,
                List.of(item("sku-001", "prod-001", 2L, 5L)),
                List.of(assignment(5L, 50L))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void construct_emptyItems_throwsIllegalArgument() {
        assertThatThrownBy(() -> new RoutePlan(UUID.randomUUID(), Instant.now(),
                List.of(),
                List.of(assignment(5L, 50L))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void construct_emptyAssignments_throwsIllegalArgument() {
        assertThatThrownBy(() -> new RoutePlan(UUID.randomUUID(), Instant.now(),
                List.of(item("sku-001", "prod-001", 2L, 5L)),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routePlanItem_blankSkuId_throwsIllegalArgument() {
        assertThatThrownBy(() -> item("  ", "prod-001", 2L, 5L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routePlanItem_blankProductId_throwsIllegalArgument() {
        assertThatThrownBy(() -> item("sku-001", "", 2L, 5L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routePlanItem_zeroQuantity_throwsIllegalArgument() {
        assertThatThrownBy(() -> item("sku-001", "prod-001", 0L, 5L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routePlanItem_zeroWarehouseId_throwsIllegalArgument() {
        assertThatThrownBy(() -> item("sku-001", "prod-001", 2L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}