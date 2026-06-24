package com.platform.inventoryservice.port.outbound.database;

import com.platform.inventoryservice.domain.entity.InventoryReservation;
import com.platform.inventoryservice.domain.enums.ReservationStatus;

import java.util.List;
import java.util.Optional;

public interface InventoryReservationRepository {
    /** Idempotent insert. Trả về Optional.empty nếu insert fail do partial UNIQUE violation (đã có row RESERVED). */
    Optional<Long> insertIfAbsent(InventoryReservation domain);
    
    /** Lookup reservation đang active (status bất kỳ) theo (orderId, skuId, warehouseId) — single row per lifecycle. */
    Optional<InventoryReservation> findActiveByOrderSkuWarehouse(long orderId, String skuId, long warehouseId);
    
    List<InventoryReservation> findByOrderId(long orderId);
    
    void updateStatus(long id, ReservationStatus status);
    
    int markRefundExpected(long orderId, String refundRequestId, List<Long> orderItemIds);
    
    int markRefundReturned(long orderId, long orderItemId);
    
    RefundCompletionSnapshot checkRefundCompletion(long orderId, String refundRequestId);
    
    List<Long> findDeductedItemIds(long orderId, long warehouseId);

    Optional<InventoryReservation> findByOrderIdAndOrderItemIdAndStatus(long orderId, long orderItemId, ReservationStatus status);

    List<InventoryReservation> findByOrderIdAndOrderItemIdInAndStatus(long orderId, List<Long> orderItemIds, ReservationStatus status);

    List<InventoryReservation> findRefundExpectedByOrderIdAndRefundRequestId(long orderId, String refundRequestId);
    
    record RefundCompletionSnapshot(
        long pendingCount,
        List<Long> requiredItemIds,
        List<Long> receivedItemIds) {}
}
