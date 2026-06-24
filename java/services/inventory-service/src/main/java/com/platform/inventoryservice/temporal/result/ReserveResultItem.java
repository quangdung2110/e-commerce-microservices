package com.platform.inventoryservice.temporal.result;

import com.platform.inventoryservice.domain.enums.ReservationStatus;

public record ReserveResultItem(
    long orderItemId,
    String skuId,
    String productId,
    long warehouseId,
    int quantity,
    ReservationStatus status
) {}
