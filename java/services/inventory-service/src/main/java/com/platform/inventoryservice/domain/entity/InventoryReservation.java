package com.platform.inventoryservice.domain.entity;

import com.platform.inventoryservice.domain.enums.ReservationStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain entity cho bảng {@code inventory_reservations}.
 * Quản lý state machine: {@code RESERVED → DEDUCTED | CANCELLED}.
 * Method mutation enforce transition — không có setter cho {@code status}.
 */
@Getter
@Setter
@NoArgsConstructor
public class InventoryReservation {

    private Long id;
    private long orderId;
    private long orderItemId;
    private String skuId;
    private String productId;
    private long warehouseId;
    private int quantity;
    private UUID routePlanId;
    private ReservationStatus status;
    private Instant expiresAt;
    private boolean refundExpected;
    private String refundRequestId;
    private Instant refundReturnedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public static InventoryReservation newReserved(
            long orderId,
            long orderItemId,
            String skuId,
            String productId,
            long warehouseId,
            int quantity,
            UUID routePlanId,
            Instant expiresAt) {
        InventoryReservation r = new InventoryReservation();
        r.orderId = orderId;
        r.orderItemId = orderItemId;
        r.skuId = skuId;
        r.productId = productId;
        r.warehouseId = warehouseId;
        r.quantity = quantity;
        r.routePlanId = routePlanId;
        r.status = ReservationStatus.RESERVED;
        r.expiresAt = expiresAt;
        r.refundExpected = false;
        return r;
    }

    public void markDeducted() {
        if (status != ReservationStatus.RESERVED) {
            throw new IllegalStateException("Only RESERVED can be deducted, was " + status);
        }
        this.status = ReservationStatus.DEDUCTED;
    }

    public void markCancelled() {
        if (status == ReservationStatus.DEDUCTED) {
            throw new IllegalStateException("Cannot cancel DEDUCTED reservation");
        }
        this.status = ReservationStatus.CANCELLED;
    }

    public void markRefundExpected(String refundRequestId) {
        this.refundExpected = true;
        this.refundRequestId = refundRequestId;
    }

    public void markRefundReturned(Instant when) {
        if (!refundExpected) {
            throw new IllegalStateException("Cannot mark returned for non-expected refund");
        }
        if (refundReturnedAt != null) {
            throw new IllegalStateException("Already marked returned");
        }
        this.refundReturnedAt = when;
    }
}