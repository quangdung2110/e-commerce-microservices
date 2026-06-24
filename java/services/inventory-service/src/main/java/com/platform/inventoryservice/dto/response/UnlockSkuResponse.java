package com.platform.inventoryservice.dto.response;

import java.time.Instant;

public record UnlockSkuResponse(
    String skuId,
    long warehouseId,
    boolean unlocked,
    Instant unlockedAt
) {}
