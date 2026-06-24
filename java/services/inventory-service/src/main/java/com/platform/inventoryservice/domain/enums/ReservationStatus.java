package com.platform.inventoryservice.domain.enums;

/**
 * Trạng thái vòng đời của một {@code inventory_reservations} row.
 * Enum này map sang cột {@code status VARCHAR(50)} với CHECK constraint.
 */
public enum ReservationStatus {

    RESERVED,
    DEDUCTED,
    CANCELLED;

    /**
     * @return {@code true} nếu reservation đã ở trạng thái kết thúc
     * (không thể chuyển tiếp sang trạng thái khác).
     */
    public boolean isTerminal() {
        return this == DEDUCTED || this == CANCELLED;
    }
}