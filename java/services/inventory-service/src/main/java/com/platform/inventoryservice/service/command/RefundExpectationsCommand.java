package com.platform.inventoryservice.service.command;

import java.util.List;

public record RefundExpectationsCommand(
    long orderId,
    String refundRequestId,
    List<Long> orderItemIds,
    String correlationId
) {
    public RefundExpectationsCommand {
        if (orderId <= 0) throw new IllegalArgumentException("orderId invalid");
        if (refundRequestId == null || refundRequestId.isBlank()) {
            throw new IllegalArgumentException("refundRequestId required");
        }
        if (orderItemIds == null || orderItemIds.isEmpty()) {
            throw new IllegalArgumentException("orderItemIds required");
        }
        orderItemIds = List.copyOf(orderItemIds);
    }
}
