package com.platform.inventoryservice.service.result;

import java.time.Instant;
import java.util.UUID;

public record ShipToCustomerResult(
    boolean success,
    long orderId,
    long warehouseId,
    Instant shippedAt,
    UUID eventId,
    int affectedItems
) {}
