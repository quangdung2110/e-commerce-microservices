package com.platform.inventoryservice.domain.enums;

/**
 * Reference type ghi vào {@code inventory_ledger.reference_type}.
 * Ràng buộc enum trong DB (V1__init_schema.sql).
 */
public enum ReferenceType {

    ORDER_RESERVED,
    ORDER_DEDUCTED,
    ORDER_CANCELLED,
    RESERVATION_EXPIRED,
    ADMIN_RESTOCK,
    ORDER_RETURNED_RESTOCK;

    /**
     * @return {@code true} nếu reference type này làm thay đổi {@code actual_quantity}
     * (dùng để validate {@code quantity_change} trong {@code InventoryLedgerEntry}).
     */
    public boolean affectsActualQuantity() {
        return this == ORDER_DEDUCTED
                || this == ADMIN_RESTOCK
                || this == ORDER_RETURNED_RESTOCK;
    }
}