package com.platform.productservice.worker.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Represents an inventory event consumed from t.inventory.events topic (Kafka).
 * Parsed from CloudEvent format emitted by Inventory Service.
 *
 * <p>Expected event type: INVENTORY_STOCK_CHANGED</p>
 *
 * <p>Payload structure (canonical, aligned with inventory-service):</p>
 * <pre>
 * {
 *   "event_id": "123e4567-e89b-12d3-a456-426614174003",
 *   "event_type": "INVENTORY_STOCK_CHANGED",
 *   "aggregate_type": "Inventory",
 *   "aggregate_id": "sku-001",
 *   "correlation_id": "sys-abc",
 *   "timestamp": "2026-02-28T10:05:01Z",
 *   "source": "inventory-service",
 *   "schema_version": 1,
 *   "payload": {
 *     "product_id": "prod-uuid-001",
 *     "sku_id": "sku-001",
 *     "available_quantity": 798,
 *     "change_type": "ORDER_RESERVED"
 *   }
 * }
 * </pre>
 *
 * <p>Important — Source of {@code event_timestamp} (epoch_ms) for staleness guard:</p>
 * <ul>
 *   <li>Payload does NOT carry {@code event_timestamp}, {@code timestamp_iso},
 *       {@code previous_available_quantity} or any time field.</li>
 *   <li>Consumer MUST derive epoch_ms from the CloudEvents wrapper {@code timestamp}
 *       (ISO 8601 UTC) via {@link #getEventTimestamp()}.</li>
 *   <li>Example: {@code "2026-02-28T10:05:01Z"} -> {@code Instant.parse(...).toEpochMilli() = 1772273101000}.</li>
 *   <li>The derived epoch_ms is passed to the Lua script as {@code ARGV[1]} and used as
 *       ZSET score / member ({@code {sku_id}:{eventTimestamp}}).</li>
 * </ul>
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

    /**
     * CloudEvents wrapper timestamp in ISO 8601 UTC (e.g. "2026-02-28T10:05:01Z").
     * Source of truth for {@link #getEventTimestamp()}.
     */
    @JsonProperty("timestamp")
    private String timestamp;

    private Map<String, Object> payload;

    /**
     * Returns the raw CloudEvents wrapper timestamp (ISO 8601 UTC).
     */
    public String getTimestamp() {
        return timestamp;
    }

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
     * Returns the {@code change_type} from the payload (e.g. ORDER_RESERVED, RESTOCK, ...).
     */
    public String getChangeType() {
        if (payload == null) return null;
        Object changeType = payload.get("change_type");
        return changeType != null ? changeType.toString() : null;
    }

    /**
     * Returns the event timestamp as epoch milliseconds.
     *
     * <p>Derived from the CloudEvents wrapper {@code timestamp} field (ISO 8601 UTC).
     * The payload intentionally does NOT carry {@code event_timestamp}.</p>
     *
     * @return epoch millis, or {@code 0L} when the wrapper timestamp is missing or unparsable.
     */
    public long getEventTimestamp() {
        if (timestamp == null || timestamp.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (DateTimeParseException e) {
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
     *
     * <p>The wrapper {@code timestamp} is mandatory because the epoch_ms used for the
     * staleness guard is derived from it. Payload-level {@code event_timestamp} is no
     * longer consulted.</p>
     */
    public boolean isValid() {
        return getSkuId() != null && !getSkuId().isBlank()
                && getProductId() != null && !getProductId().isBlank()
                && getTimestamp() != null && !getTimestamp().isBlank()
                && getEventTimestamp() > 0L;
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
