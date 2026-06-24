package com.platform.inventoryservice.temporal.command;

import java.util.List;
import java.util.UUID;

public record ReserveCommand(
    long orderId,
    UUID routePlanId,
    List<ReserveCommandItem> items
) {
    public ReserveCommand {
        if (orderId <= 0) throw new IllegalArgumentException("orderId invalid");
        if (routePlanId == null) throw new IllegalArgumentException("routePlanId required");
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("items required");
        items = List.copyOf(items);
    }
}
