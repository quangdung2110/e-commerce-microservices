package com.platform.inventoryservice.temporal.command;

import com.platform.inventoryservice.domain.enums.ChangeType;
import java.util.List;

public record RollbackCommand(
    long orderId,
    List<RollbackCommandItem> items,
    ChangeType changeType
) {
    public RollbackCommand {
        if (orderId <= 0) throw new IllegalArgumentException("orderId invalid");
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("items required");
        items = List.copyOf(items);
        if (changeType != ChangeType.ORDER_CANCELLED && changeType != ChangeType.RESERVATION_EXPIRED) {
            throw new IllegalArgumentException("Unsupported changeType: " + changeType);
        }
    }
}
