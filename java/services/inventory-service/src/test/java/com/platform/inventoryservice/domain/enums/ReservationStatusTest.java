package com.platform.inventoryservice.domain.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationStatusTest {

    @Test
    void isTerminal_deductedAndCancelled_true() {
        assertThat(ReservationStatus.DEDUCTED.isTerminal()).isTrue();
        assertThat(ReservationStatus.CANCELLED.isTerminal()).isTrue();
    }

    @Test
    void isTerminal_reserved_false() {
        assertThat(ReservationStatus.RESERVED.isTerminal()).isFalse();
    }
}

class OutboxEventTypeTest {

    @Test
    void aggregateType_inventory() {
        for (OutboxEventType t : OutboxEventType.values()) {
            assertThat(t.aggregateType()).isEqualTo("Inventory");
        }
    }

    @Test
    void topic_inventoryEvents() {
        for (OutboxEventType t : OutboxEventType.values()) {
            assertThat(t.topic()).isEqualTo("t.inventory.events");
        }
    }

    @Test
    void defaultPartitionKey_nullForAll() {
        for (OutboxEventType t : OutboxEventType.values()) {
            assertThat(t.defaultPartitionKey()).isNull();
        }
    }
}