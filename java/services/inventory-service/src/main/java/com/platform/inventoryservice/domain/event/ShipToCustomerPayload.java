package com.platform.inventoryservice.domain.event;

import java.time.Instant;

/**
 * Payload cho event {@code INVENTORY_SHIP_TO_CUSTOMER}.
 * Signal nghiệp vụ cho Order Service chuyển {@code order_items.status} PROCESSING → DELIVERED.
 */
public record ShipToCustomerPayload(
        long orderId,
        long warehouseId,
        Instant shippedAt) {
}