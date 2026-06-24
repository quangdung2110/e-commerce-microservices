package com.platform.inventoryservice.domain.event;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;

/**
 * Payload cho event {@code INVENTORY_RETURN_RECEIVED_ALL}.
 * Signal nghiệp vụ cho Order Service mở bước refund tiền.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReturnReceivedAllPayload(
        long orderId,
        String refundRequestId,
        List<Long> orderItemIds,
        Instant confirmedAt) {
}