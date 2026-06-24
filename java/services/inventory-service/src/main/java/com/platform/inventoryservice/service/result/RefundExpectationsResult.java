package com.platform.inventoryservice.service.result;

public record RefundExpectationsResult(
    long orderId,
    String refundRequestId,
    int markedCount
) {}
