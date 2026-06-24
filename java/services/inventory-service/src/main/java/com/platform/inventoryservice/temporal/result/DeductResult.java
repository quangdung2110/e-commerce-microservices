package com.platform.inventoryservice.temporal.result;

import java.time.Instant;
import java.util.List;

public record DeductResult(
    long orderId,
    List<DeductResultItem> items,
    Instant deductedAt
) {
    public DeductResult {
        items = List.copyOf(items);
    }
}
