package com.platform.inventoryservice.temporal.result;

import com.platform.inventoryservice.domain.enums.ChangeType;
import java.time.Instant;
import java.util.List;

public record RollbackResult(
    long orderId,
    List<RollbackResultItem> items,
    ChangeType changeType,
    Instant rolledBackAt
) {
    public RollbackResult {
        items = List.copyOf(items);
    }
}
