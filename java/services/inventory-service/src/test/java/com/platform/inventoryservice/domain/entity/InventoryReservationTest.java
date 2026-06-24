package com.platform.inventoryservice.domain.entity;

import com.platform.inventoryservice.domain.enums.ReservationStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryReservationTest {

    private static InventoryReservation reserved() {
        return InventoryReservation.newReserved(
                10001L, 2001L, "sku-001", "prod-001", 5L, 2,
                UUID.randomUUID(), Instant.now().plusSeconds(1800));
    }

    @Test
    void newReserved_setsStatusReserved() {
        InventoryReservation r = reserved();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(r.isRefundExpected()).isFalse();
        assertThat(r.getRefundRequestId()).isNull();
    }

    @Test
    void markDeducted_fromReserved_success() {
        InventoryReservation r = reserved();
        r.markDeducted();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.DEDUCTED);
    }

    @Test
    void markDeducted_fromCancelled_throwsIllegalState() {
        InventoryReservation r = reserved();
        r.markCancelled();
        assertThatThrownBy(r::markDeducted)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only RESERVED");
    }

    @Test
    void markDeducted_fromDeducted_throwsIllegalState() {
        InventoryReservation r = reserved();
        r.markDeducted();
        assertThatThrownBy(r::markDeducted)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markCancelled_fromReserved_success() {
        InventoryReservation r = reserved();
        r.markCancelled();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void markCancelled_fromDeducted_throwsIllegalState() {
        InventoryReservation r = reserved();
        r.markDeducted();
        assertThatThrownBy(r::markCancelled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot cancel DEDUCTED");
    }

    @Test
    void markRefundExpected_setsFlag() {
        InventoryReservation r = reserved();
        r.markRefundExpected("rr-2026-0001");
        assertThat(r.isRefundExpected()).isTrue();
        assertThat(r.getRefundRequestId()).isEqualTo("rr-2026-0001");
    }

    @Test
    void markRefundReturned_withoutExpected_throwsIllegalState() {
        InventoryReservation r = reserved();
        assertThatThrownBy(() -> r.markRefundReturned(Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-expected refund");
    }

    @Test
    void markRefundReturned_firstTime_success() {
        InventoryReservation r = reserved();
        r.markRefundExpected("rr-001");
        Instant when = Instant.now();
        r.markRefundReturned(when);
        assertThat(r.getRefundReturnedAt()).isEqualTo(when);
    }

    @Test
    void markRefundReturned_twice_throwsIllegalState() {
        InventoryReservation r = reserved();
        r.markRefundExpected("rr-001");
        r.markRefundReturned(Instant.now());
        assertThatThrownBy(() -> r.markRefundReturned(Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Already marked returned");
    }
}