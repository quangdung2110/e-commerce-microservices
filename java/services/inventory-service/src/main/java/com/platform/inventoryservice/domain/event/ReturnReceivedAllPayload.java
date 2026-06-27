package com.platform.inventoryservice.domain.event;

/**
 * Payload cho event {@code INVENTORY_RETURN_RECEIVED_ALL}.
 * Signal nghiệp vụ cho Order Service mở bước refund tiền.
 * <p>Phase 20: đã bỏ {@code orderItemIds} (Order derive từ {@code refundRequestId} + {@code orderId})
 * và {@code confirmedAt} (trùng envelope {@code timestamp}).</p>
 */
public record ReturnReceivedAllPayload(
        long orderId,
        String refundRequestId) {
}