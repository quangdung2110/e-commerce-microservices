package com.platform.inventoryservice.service.result;

import java.time.Instant;

public record UnlockSkuResult(
    String skuId,
    long warehouseId,
    boolean unlocked,
    Instant unlockedAt
) {}
