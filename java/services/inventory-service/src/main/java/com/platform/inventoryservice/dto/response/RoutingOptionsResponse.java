package com.platform.inventoryservice.dto.response;

import com.platform.inventoryservice.domain.vo.RoutePlan;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoutingOptionsResponse(
    UUID routePlanId,
    Instant expiresAt,
    List<RoutePlanItemDto> items,
    List<WarehouseAssignmentDto> warehouseAssignments
) {
    public record RoutePlanItemDto(
        String skuId,
        String productId,
        int quantity,
        long warehouseId
    ) {}

    public record WarehouseAssignmentDto(
        long warehouseId,
        long availableQuantity
    ) {}

    public static RoutingOptionsResponse from(RoutePlan plan) {
        return new RoutingOptionsResponse(
            plan.routePlanId(),
            plan.expiresAt(),
            plan.items().stream()
                .map(i -> new RoutePlanItemDto(
                    i.skuId(), i.productId(),
                    (int) i.quantity(), i.warehouseId()))
                .toList(),
            plan.warehouseAssignments().stream()
                .map(w -> new WarehouseAssignmentDto(
                    w.warehouseId(), w.availableQuantity()))
                .toList()
        );
    }
}
