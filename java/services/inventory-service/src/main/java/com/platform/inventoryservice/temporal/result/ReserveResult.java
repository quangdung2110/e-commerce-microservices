package com.platform.inventoryservice.temporal.result;

import java.time.Instant;
import java.util.List;

public record ReserveResult(
    long orderId,
    List<ReserveResultItem> items,
    Instant reservedAt,
    Instant expiresAt
) {
    public ReserveResult {
        items = List.copyOf(items);
    }
}
