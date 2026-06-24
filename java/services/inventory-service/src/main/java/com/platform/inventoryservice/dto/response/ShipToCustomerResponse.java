package com.platform.inventoryservice.dto.response;

import com.platform.inventoryservice.service.result.ShipToCustomerResult;

import java.time.Instant;
import java.util.UUID;

public record ShipToCustomerResponse(
    boolean success,
    long orderId,
    long warehouseId,
    Instant shippedAt,
    UUID eventId,
    int affectedItems
) {
    public static ShipToCustomerResponse from(ShipToCustomerResult result) {
        return new ShipToCustomerResponse(
            true,
            result.orderId(),
            result.warehouseId(),
            result.shippedAt(),
            result.eventId(),
            result.affectedItems()
        );
    }
}
