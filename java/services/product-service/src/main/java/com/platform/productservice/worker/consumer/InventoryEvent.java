package com.platform.productservice.worker.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Represents an inventory event consumed from t.inventory.events topic (Kafka).
 * Parsed from CloudEvent format emitted by Inventory Service.
 *
 * <p>Expected event type: INVENTORY_QUANTITY_CHANGED</p>
 *
 * <p>Payload structure:</p>
 * <pre>
 * {
 *   "event_id": "...",
 *   "event_type": "INVENTORY_QUANTITY_CHANGED",
 *   "schema_version": 1,
 *   "payload": {
 *     "product_id": "...",
 *     "sku_id": "...",
 *     "available_quantity": 0,
 *     "previous_available_quantity": 15,
 *     "change_type": "ORDER_DEDUCTED",
 *     "event_timestamp": 1709420000000,
 *     "timestamp_iso": "2026-02-28T08:00:00Z"
 *   }
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InventoryEvent {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("schema_version")
    private String schemaVersion;

    private Map<String, Object> payload;

    /**
     * Returns the SKU ID from the payload.
     */
    public String getSkuId() {
        if (payload == null) return null;
        Object skuId = payload.get("sku_id");
        return skuId != null ? skuId.toString() : null;
    }

    /**
     * Returns the product ID from the payload.
     */
    public String getProductId() {
        if (payload == null) return null;
        Object productId = payload.get("product_id");
        return productId != null ? productId.toString() : null;
    }

    /**
     * Returns the available quantity from the payload.
     */
    public int getAvailableQuantity() {
        if (payload == null) return 0;
        Object qty = payload.get("available_quantity");
        if (qty == null) return 0;
        if (qty instanceof Number) {
            return ((Number) qty).intValue();
        }
        try {
            return Integer.parseInt(qty.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Returns the event timestamp (epoch millis) from the payload.
     */
    public long getEventTimestamp() {
        if (payload == null) return 0L;
        Object ts = payload.get("event_timestamp");
        if (ts == null) return 0L;
        if (ts instanceof Number) {
            return ((Number) ts).longValue();
        }
        try {
            return Long.parseLong(ts.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Returns the event ID from the top-level field.
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * Validates that required fields are present.
     */
    public boolean isValid() {
        return getSkuId() != null && !getSkuId().isBlank()
                && getProductId() != null && !getProductId().isBlank()
                && getEventTimestamp() > 0;
    }

    /**
     * Derives the SKU status based on available quantity.
     * This matches the contract defined in product_events.md.
     */
    public String deriveSkuStatus() {
        int qty = getAvailableQuantity();
        if (qty <= 0) {
            return "OUT_OF_STOCK";
        }
        return "ACTIVE";
    }
}
