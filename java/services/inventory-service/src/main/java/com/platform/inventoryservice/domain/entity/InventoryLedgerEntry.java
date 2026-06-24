package com.platform.inventoryservice.domain.entity;

import com.platform.inventoryservice.domain.enums.ReferenceType;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain entity cho bảng {@code inventory_ledger} (append-only audit trail).
 * Không có setter — chỉ tạo qua factory {@link #append(UUID, String, String, long, ReferenceType, int, int, String)}.
 */
@Getter
public class InventoryLedgerEntry {

    private Long id;
    private UUID eventId;
    private String skuId;
    private String productId;
    private long warehouseId;
    private ReferenceType referenceType;
    private int quantityChange;
    private int balanceAfter;
    private String correlationId;
    private Instant createdAt;

    public InventoryLedgerEntry() {
    }

    public static InventoryLedgerEntry append(
            UUID eventId,
            String skuId,
            String productId,
            long warehouseId,
            ReferenceType referenceType,
            int quantityChange,
            int balanceAfter,
            String correlationId) {
        if (quantityChange == 0 && referenceType.affectsActualQuantity()) {
            throw new IllegalArgumentException(
                    "Reference type " + referenceType + " must affect actual_quantity");
        }
        if (quantityChange != 0 && !referenceType.affectsActualQuantity()) {
            throw new IllegalArgumentException(
                    "Reference type " + referenceType + " must NOT change actual_quantity");
        }
        InventoryLedgerEntry e = new InventoryLedgerEntry();
        e.eventId = eventId;
        e.skuId = skuId;
        e.productId = productId;
        e.warehouseId = warehouseId;
        e.referenceType = referenceType;
        e.quantityChange = quantityChange;
        e.balanceAfter = balanceAfter;
        e.correlationId = correlationId;
        return e;
    }
}