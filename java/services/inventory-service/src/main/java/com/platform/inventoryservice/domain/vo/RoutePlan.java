package com.platform.inventoryservice.domain.vo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Route plan fulfillment cho checkout. Immutable record; instance sinh ra từ
 * {@code RoutePlanBuilder} (M10).
 */
public record RoutePlan(
        UUID routePlanId,
        Instant expiresAt,
        List<RoutePlanItem> items,
        List<WarehouseAssignment> warehouseAssignments) {

    public RoutePlan {
        if (routePlanId == null) {
            throw new IllegalArgumentException("routePlanId required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt required");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items required");
        }
        if (warehouseAssignments == null || warehouseAssignments.isEmpty()) {
            throw new IllegalArgumentException("warehouseAssignments required");
        }
        items = List.copyOf(items);
        warehouseAssignments = List.copyOf(warehouseAssignments);
    }

    public record RoutePlanItem(
            String skuId,
            String productId,
            long quantity,
            long warehouseId) {

        public RoutePlanItem {
            if (skuId == null || skuId.isBlank()) {
                throw new IllegalArgumentException("skuId required");
            }
            if (productId == null || productId.isBlank()) {
                throw new IllegalArgumentException("productId required");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
            if (warehouseId <= 0) {
                throw new IllegalArgumentException("warehouseId must be positive");
            }
        }
    }

    public record WarehouseAssignment(long warehouseId, long availableQuantity) {
    }
}