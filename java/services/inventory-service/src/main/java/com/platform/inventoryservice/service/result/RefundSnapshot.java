package com.platform.inventoryservice.service.result;

import java.time.Instant;

public record RefundSnapshot(
    long orderId, 
    long orderItemId, 
    String refundRequestId,
    Instant refundReturnedAt, 
    boolean allItemsReceived
) {}
