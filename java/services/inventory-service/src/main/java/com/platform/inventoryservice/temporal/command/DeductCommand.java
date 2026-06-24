package com.platform.inventoryservice.temporal.command;

import java.util.List;

public record DeductCommand(
    long orderId,
    List<DeductCommandItem> items
) {
    public DeductCommand {
        if (orderId <= 0) throw new IllegalArgumentException("orderId invalid");
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("items required");
        items = List.copyOf(items);
    }
}
