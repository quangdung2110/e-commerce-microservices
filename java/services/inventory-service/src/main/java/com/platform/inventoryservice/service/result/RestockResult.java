package com.platform.inventoryservice.service.result;

import com.platform.inventoryservice.domain.enums.ReferenceType;

import java.time.Instant;
import java.util.UUID;

public record RestockResult(
    long warehouseId,
    String skuId,
    String productId,
    ReferenceType referenceType,
    int quantityAdded,
    StockSnapshot physical,
    RefundSnapshot refund,
    UUID eventId,
    Instant timestamp
) {}
