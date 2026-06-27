package com.platform.productservice.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.common.constants.ServiceConstants;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void wrapperTimestamp_iso8601_convertsToEpochMillis() {
        InventoryEvent event = InventoryEvent.builder()
                .eventId("evt-001")
                .eventType(ServiceConstants.EVENT_TYPE_INVENTORY_STOCK_CHANGED)
                .timestamp("2026-02-28T10:05:01Z")
                .build();

        long epochMs = event.getEventTimestamp();

        assertEquals(1772273101000L, epochMs,
                "ISO 8601 UTC wrapper timestamp must be parsed to epoch millis");
    }

    @Test
    void wrapperTimestamp_blank_returnsZero() {
        InventoryEvent event = InventoryEvent.builder()
                .eventId("evt-002")
                .eventType(ServiceConstants.EVENT_TYPE_INVENTORY_STOCK_CHANGED)
                .timestamp(" ")
                .build();

        assertEquals(0L, event.getEventTimestamp());
    }

    @Test
    void wrapperTimestamp_invalidIso_returnsZero() {
        InventoryEvent event = InventoryEvent.builder()
                .eventId("evt-003")
                .eventType(ServiceConstants.EVENT_TYPE_INVENTORY_STOCK_CHANGED)
                .timestamp("not-a-timestamp")
                .build();

        assertEquals(0L, event.getEventTimestamp());
    }

    @Test
    void isValid_requiresWrapperTimestamp_notPayloadEventTimestamp() {
        InventoryEvent event = InventoryEvent.builder()
                .eventId("evt-004")
                .eventType(ServiceConstants.EVENT_TYPE_INVENTORY_STOCK_CHANGED)
                .timestamp(null)
                .payload(Map.of(
                        "product_id", "prod-001",
                        "sku_id", "sku-001",
                        "available_quantity", 10,
                        "change_type", "ORDER_RESERVED",
                        "event_timestamp", 1772289901000L
                ))
                .build();

        assertFalse(event.isValid(),
                "Payload-level event_timestamp must be ignored; wrapper timestamp is mandatory");
    }

    @Test
    void isValid_acceptsEventWithWrapperTimestampAndPayload() {
        InventoryEvent event = InventoryEvent.builder()
                .eventId("evt-005")
                .eventType(ServiceConstants.EVENT_TYPE_INVENTORY_STOCK_CHANGED)
                .timestamp("2026-02-28T10:05:01Z")
                .payload(Map.of(
                        "product_id", "prod-001",
                        "sku_id", "sku-001",
                        "available_quantity", 798,
                        "change_type", "ORDER_RESERVED"
                ))
                .build();

        assertTrue(event.isValid());
    }

    @Test
    void inventoryStockChangedEventType_isExposed() {
        InventoryEvent event = InventoryEvent.builder()
                .eventType(ServiceConstants.EVENT_TYPE_INVENTORY_STOCK_CHANGED)
                .timestamp("2026-02-28T10:05:01Z")
                .build();

        assertEquals("INVENTORY_STOCK_CHANGED", event.getEventType());
    }

    @Test
    void payloadFields_availableQuantity_changeType_productId_skuId_areReadable() {
        InventoryEvent event = InventoryEvent.builder()
                .eventType(ServiceConstants.EVENT_TYPE_INVENTORY_STOCK_CHANGED)
                .timestamp("2026-02-28T10:05:01Z")
                .payload(Map.of(
                        "product_id", "prod-001",
                        "sku_id", "sku-001",
                        "available_quantity", 798,
                        "change_type", "ORDER_RESERVED"
                ))
                .build();

        assertEquals("prod-001", event.getProductId());
        assertEquals("sku-001", event.getSkuId());
        assertEquals(798, event.getAvailableQuantity());
        assertEquals("ORDER_RESERVED", event.getChangeType());
    }

    @Test
    void deriveSkuStatus_zeroQuantityIsOutOfStock() {
        InventoryEvent event = InventoryEvent.builder()
                .eventType(ServiceConstants.EVENT_TYPE_INVENTORY_STOCK_CHANGED)
                .timestamp("2026-02-28T10:05:01Z")
                .payload(Map.of(
                        "product_id", "prod-001",
                        "sku_id", "sku-001",
                        "available_quantity", 0,
                        "change_type", "ORDER_DEDUCTED"
                ))
                .build();

        assertEquals("OUT_OF_STOCK", event.deriveSkuStatus());
    }

    @Test
    void deriveSkuStatus_positiveQuantityIsActive() {
        InventoryEvent event = InventoryEvent.builder()
                .eventType(ServiceConstants.EVENT_TYPE_INVENTORY_STOCK_CHANGED)
                .timestamp("2026-02-28T10:05:01Z")
                .payload(Map.of(
                        "product_id", "prod-001",
                        "sku_id", "sku-001",
                        "available_quantity", 798,
                        "change_type", "RESTOCK"
                ))
                .build();

        assertEquals("ACTIVE", event.deriveSkuStatus());
    }

    @Test
    void deserialization_fullCanonicalJson_extractsAllFields() throws Exception {
        String json = "{\n" +
                "  \"event_id\": \"123e4567-e89b-12d3-a456-426614174003\",\n" +
                "  \"event_type\": \"INVENTORY_STOCK_CHANGED\",\n" +
                "  \"aggregate_type\": \"Inventory\",\n" +
                "  \"aggregate_id\": \"sku-001\",\n" +
                "  \"correlation_id\": \"sys-abc\",\n" +
                "  \"timestamp\": \"2026-02-28T10:05:01Z\",\n" +
                "  \"source\": \"inventory-service\",\n" +
                "  \"schema_version\": 1,\n" +
                "  \"payload\": {\n" +
                "    \"product_id\": \"prod-uuid-001\",\n" +
                "    \"sku_id\": \"sku-001\",\n" +
                "    \"available_quantity\": 798,\n" +
                "    \"change_type\": \"ORDER_RESERVED\"\n" +
                "  }\n" +
                "}";

        InventoryEvent event = objectMapper.readValue(json, InventoryEvent.class);

        assertNotNull(event);
        assertEquals("123e4567-e89b-12d3-a456-426614174003", event.getEventId());
        assertEquals("INVENTORY_STOCK_CHANGED", event.getEventType());
        assertEquals("2026-02-28T10:05:01Z", event.getTimestamp());
        assertEquals(1772273101000L, event.getEventTimestamp());
        assertEquals("prod-uuid-001", event.getProductId());
        assertEquals("sku-001", event.getSkuId());
        assertEquals(798, event.getAvailableQuantity());
        assertEquals("ORDER_RESERVED", event.getChangeType());
        assertTrue(event.isValid());
    }
}
