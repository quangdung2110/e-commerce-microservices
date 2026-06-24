package com.platform.inventoryservice.domain.enums;

/**
 * {@code change_type} của event {@code INVENTORY_STOCK_MUTATION} (phát ra từ Lua scripts).
 * Tập con của {@link ReferenceType}: chỉ chứa các giá trị phát sinh từ hot path.
 */
public enum ChangeType {

    ORDER_RESERVED,
    ORDER_DEDUCTED,
    ORDER_CANCELLED,
    RESERVATION_EXPIRED;

    /**
     * Map {@link ReferenceType} sang {@code ChangeType}.
     *
     * @throws IllegalStateException nếu {@code rt} là {@code ADMIN_RESTOCK}
     *         hoặc {@code ORDER_RETURNED_RESTOCK} — 2 giá trị này đi qua API → DB outbox,
     *         KHÔNG bao giờ xuất hiện trong {@code INVENTORY_STOCK_MUTATION}.
     */
    public static ChangeType fromReferenceType(ReferenceType rt) {
        return switch (rt) {
            case ORDER_RESERVED -> ORDER_RESERVED;
            case ORDER_DEDUCTED -> ORDER_DEDUCTED;
            case ORDER_CANCELLED -> ORDER_CANCELLED;
            case RESERVATION_EXPIRED -> RESERVATION_EXPIRED;
            default -> throw new IllegalStateException("Cannot map to ChangeType: " + rt);
        };
    }
}