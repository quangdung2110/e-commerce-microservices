package com.platform.inventoryservice.infrastructure.persistence.jpa.custom;

import com.platform.inventoryservice.domain.enums.ReservationStatus;
import com.platform.inventoryservice.infrastructure.persistence.jpa.entity.InventoryReservationJpaEntity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InventoryReservationJpaCustom {

    /** Idempotent insert với ON CONFLICT DO NOTHING (partial UNIQUE trên (order_id, sku_id, warehouse_id) WHERE status='RESERVED'). */
    Optional<Long> insertIfAbsent(InventoryReservationJpaEntity entity);

    /** Bulk update status theo danh sách (id). */
    int bulkUpdateStatus(Collection<ReservationStatusUpdate> updates);

    /** Query refund completion check. */
    RefundCompletionProjection findRefundCompletion(long orderId, String refundRequestId);

    /** Mark refund expectation (idempotent). */
    int markRefundExpected(long orderId, String refundRequestId, List<Long> orderItemIds);

    /** Mark refund returned cho 1 reservation. */
    int markRefundReturned(long orderId, long orderItemId);

    /** Query order_item_ids DEDUCTED tại 1 warehouse cho ship-to-customer. */
    List<Long> findDeductedItemIds(long orderId, long warehouseId);

    record ReservationStatusUpdate(long id, ReservationStatus newStatus) {}
    record RefundCompletionProjection(long pendingCount, List<Long> requiredItemIds, List<Long> receivedItemIds) {}
}
