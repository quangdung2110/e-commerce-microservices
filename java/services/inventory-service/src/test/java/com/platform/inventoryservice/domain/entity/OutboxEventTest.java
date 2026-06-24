package com.platform.inventoryservice.domain.entity;

import com.platform.inventoryservice.domain.enums.OutboxEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    @Test
    void of_generatesEventIdAndSetsDefaults() {
        OutboxEvent e = OutboxEvent.of(OutboxEventType.INVENTORY_STOCK_CHANGED,
                "sku-001", "sku-001", "{}");

        assertThat(e.getEventId()).isNotNull();
        assertThat(e.getSchemaVersion()).isEqualTo(1);
        assertThat(e.getAggregateType()).isEqualTo("Inventory");
        assertThat(e.getEventType()).isEqualTo(OutboxEventType.INVENTORY_STOCK_CHANGED);
        assertThat(e.getAggregateId()).isEqualTo("sku-001");
        assertThat(e.getPartitionKey()).isEqualTo("sku-001");
        assertThat(e.getPayload()).isEqualTo("{}");
        assertThat(e.getRefundRequestId()).isNull();
        assertThat(e.getCreatedAt()).isNotNull();
    }

    @Test
    void of_withRefundRequestId_setsField() {
        OutboxEvent e = OutboxEvent.of(OutboxEventType.INVENTORY_RETURN_RECEIVED_ALL,
                "10001", "10001", "{}", "rr-2026-0001");

        assertThat(e.getRefundRequestId()).isEqualTo("rr-2026-0001");
        assertThat(e.isReturnReceivedAll()).isTrue();
    }

    @Test
    void of_eachCall_generatesDistinctEventId() {
        OutboxEvent a = OutboxEvent.of(OutboxEventType.INVENTORY_SHIP_TO_CUSTOMER,
                "10001", "10001", "{}");
        OutboxEvent b = OutboxEvent.of(OutboxEventType.INVENTORY_SHIP_TO_CUSTOMER,
                "10001", "10001", "{}");
        assertThat(a.getEventId()).isNotEqualTo(b.getEventId());
    }

    @Test
    void isReturnReceivedAll_trueOnlyForReturnReceivedAll() {
        OutboxEvent e1 = OutboxEvent.of(OutboxEventType.INVENTORY_STOCK_CHANGED,
                "sku", "sku", "{}");
        OutboxEvent e2 = OutboxEvent.of(OutboxEventType.INVENTORY_SHIP_TO_CUSTOMER,
                "10001", "10001", "{}");
        OutboxEvent e3 = OutboxEvent.of(OutboxEventType.INVENTORY_RETURN_RECEIVED_ALL,
                "10001", "10001", "{}");

        assertThat(e1.isReturnReceivedAll()).isFalse();
        assertThat(e2.isReturnReceivedAll()).isFalse();
        assertThat(e3.isReturnReceivedAll()).isTrue();
    }
}