package com.platform.inventoryservice.service.command;

import java.time.Instant;

public record ShipToCustomerCommand(
    long orderId,
    long warehouseId,
    Instant shippedAt,
    String courierNote,
    String correlationId
) {
    public ShipToCustomerCommand {
        if (orderId <= 0) throw new IllegalArgumentException("orderId invalid");
        if (warehouseId <= 0) throw new IllegalArgumentException("warehouseId invalid");
        if (shippedAt == null) throw new IllegalArgumentException("shippedAt required");
    }
}
