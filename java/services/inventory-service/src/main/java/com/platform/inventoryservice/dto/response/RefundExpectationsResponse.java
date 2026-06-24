package com.platform.inventoryservice.dto.response;

public record RefundExpectationsResponse(
    long orderId,
    String refundRequestId,
    int markedCount
) {}
