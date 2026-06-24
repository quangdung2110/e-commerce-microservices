package com.platform.inventoryservice.temporal.result;

import com.platform.inventoryservice.domain.enums.ReservationStatus;

public record DeductResultItem(
    long orderItemId,
    String skuId,
    long warehouseId,
    int quantity,
    ReservationStatus status
) {}
