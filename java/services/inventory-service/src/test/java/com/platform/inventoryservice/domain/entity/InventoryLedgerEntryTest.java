package com.platform.inventoryservice.domain.entity;

import com.platform.inventoryservice.domain.enums.ReferenceType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryLedgerEntryTest {

    private static InventoryLedgerEntry append(ReferenceType rt, int qty, int balance) {
        return InventoryLedgerEntry.append(
                UUID.randomUUID(), "sku-001", "prod-001", 5L,
                rt, qty, balance, "corr-001");
    }

    @Test
    void append_deducted_negativeQty_success() {
        InventoryLedgerEntry e = append(ReferenceType.ORDER_DEDUCTED, -2, 98);
        assertThat(e.getQuantityChange()).isEqualTo(-2);
        assertThat(e.getBalanceAfter()).isEqualTo(98);
        assertThat(e.getReferenceType()).isEqualTo(ReferenceType.ORDER_DEDUCTED);
    }

    @Test
    void append_adminRestock_positiveQty_success() {
        InventoryLedgerEntry e = append(ReferenceType.ADMIN_RESTOCK, 100, 200);
        assertThat(e.getQuantityChange()).isEqualTo(100);
    }

    @Test
    void append_orderReserved_zeroQty_success() {
        InventoryLedgerEntry e = append(ReferenceType.ORDER_RESERVED, 0, 100);
        assertThat(e.getQuantityChange()).isZero();
        assertThat(e.getReferenceType()).isEqualTo(ReferenceType.ORDER_RESERVED);
    }

    @Test
    void append_deducted_zeroQty_throwsIllegalArgument() {
        assertThatThrownBy(() -> append(ReferenceType.ORDER_DEDUCTED, 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must affect actual_quantity");
    }

    @Test
    void append_adminRestock_zeroQty_throwsIllegalArgument() {
        assertThatThrownBy(() -> append(ReferenceType.ADMIN_RESTOCK, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void append_orderReturnedRestock_zeroQty_throwsIllegalArgument() {
        assertThatThrownBy(() -> append(ReferenceType.ORDER_RETURNED_RESTOCK, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void append_orderReserved_nonzeroQty_throwsIllegalArgument() {
        assertThatThrownBy(() -> append(ReferenceType.ORDER_RESERVED, 5, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must NOT change actual_quantity");
    }

    @Test
    void append_orderCancelled_nonzeroQty_throwsIllegalArgument() {
        assertThatThrownBy(() -> append(ReferenceType.ORDER_CANCELLED, 5, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void append_reservationExpired_nonzeroQty_throwsIllegalArgument() {
        assertThatThrownBy(() -> append(ReferenceType.RESERVATION_EXPIRED, 5, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void append_setsCorrelationId() {
        InventoryLedgerEntry e = InventoryLedgerEntry.append(
                UUID.randomUUID(), "sku-001", "prod-001", 5L,
                ReferenceType.ADMIN_RESTOCK, 10, 110, "wf-10001");
        assertThat(e.getCorrelationId()).isEqualTo("wf-10001");
    }
}