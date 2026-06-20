# Module 07: Event Definitions & Payloads

> **Depends on:** M04 (Domain Model)
> **Depended by:** M08 (Outbox & CDC), M09 (Workers), M11 (Temporal Activities), M12 (Admin Services)

---

## 1. Goal

Định nghĩa toàn bộ event structures phát ra bởi Inventory Service:

- **CloudEvents envelope** wrapper chuẩn cho mọi Kafka message.
- **4 event payload types** (`INVENTORY_STOCK_MUTATION`, `INVENTORY_STOCK_CHANGED`, `INVENTORY_RETURN_RECEIVED_ALL`, `INVENTORY_SHIP_TO_CUSTOMER`).
- **Event builder**: convert domain state → envelope + payload JSON.
- **Stock mutation payload builder**: build JSON cho ARGV[4] của Lua scripts.
- **Serializer**: Jackson-based, đảm bảo ISO 8601 UTC timestamps.

> Tham chiếu: `inventory_events.md` §3, §4.

---

## 2. Package Layout

```
com.platform.inventoryservice.domain/event/
├── CloudEvent.java                       # interface (M04)
├── StockMutationPayload.java             # record (M04)
├── StockChangedPayload.java              # record (M04)
├── ReturnReceivedAllPayload.java         # record (M04)
└── ShipToCustomerPayload.java            # record (M04)

com.platform.inventoryservice.event/      # infrastructure (impl + builders + serializer)
├── InventoryEventBuilder.java            # Main entry: domain → CloudEvent
├── StockMutationPayloadBuilder.java      # Build JSON for Lua ARGV[4]
├── EventSerializer.java                  # CloudEvent → JSON string for Kafka
├── EventDeserializer.java                # Kafka JSON → CloudEvent
├── PartitionKeyResolver.java             # (aggregate, eventType) → partition key
└── SourceConstants.java                  # "inventory-service"
```

---

## 3. CloudEvents Wrapper Standard

Tất cả Kafka messages từ Inventory Service tuân theo CloudEvents spec của repo (xem `docs/_contracts/CONTRACT_MATRIX.md` §0.2):

| Field | Type | Required | Nguồn |
|:---|:---|:---:|:---|
| `event_id` | UUID (string) | ✅ | `UUID.randomUUID()` cho outbox; payload `event_id` cho mutation |
| `event_type` | String | ✅ | `INVENTORY_STOCK_MUTATION` / `INVENTORY_STOCK_CHANGED` / `INVENTORY_RETURN_RECEIVED_ALL` / `INVENTORY_SHIP_TO_CUSTOMER` |
| `aggregate_type` | String | ✅ | Constant `"Inventory"` |
| `aggregate_id` | String | ✅ | `sku_id` (stock events) hoặc `order_id` (return/ship) |
| `correlation_id` | String | ✅ | Temporal Workflow ID hoặc HTTP `X-Correlation-Id` |
| `timestamp` | ISO 8601 UTC | ✅ | `Instant.now()` |
| `source` | String | ✅ | Constant `"inventory-service"` |
| `schema_version` | Integer | ✅ | Always `1` |
| `payload` | Object | ✅ | Event-specific payload (xem §5) |

> ⚠️ Payload KHÔNG chứa `event_timestamp` / `occurred_at` / `timestamp_iso` — wrapper `timestamp` là canonical.

---

## 4. Source Constants

```java
package com.platform.inventoryservice.event;

public final class SourceConstants {
    public static final String SERVICE_SOURCE = "inventory-service";
    public static final String AGGREGATE_TYPE = "Inventory";
    public static final int SCHEMA_VERSION = 1;
    private SourceConstants() {}
}
```

---

## 5. Event Payload Definitions

### 5.1 StockMutationPayload (internal, hot track)

**Producer:** Lua scripts trong Redis Stream → Relay Worker (M09) → Kafka `t.inventory.events`
**Consumer chính:** Kafka Ledger Worker (internal, M09)
**Partition key:** `sku_id`

```java
package com.platform.inventoryservice.domain.event;

public record StockMutationPayload(
    String skuId,
    String productId,
    long warehouseId,
    String changeType,         // ORDER_RESERVED | ORDER_DEDUCTED | ORDER_CANCELLED | RESERVATION_EXPIRED
    int quantity,
    long availableAfter,       // per-warehouse, AFTER mutation
    long reservedAfter,        // per-warehouse, AFTER mutation
    long orderId,
    long orderItemId
) {
    public String toJsonString() {
        return EventSerializer.toJson(this);
    }
}
```

> `event_id` được set riêng tại envelope level (M07 §3) — payload này KHÔNG có `event_id` field.

### 5.2 StockChangedPayload (public, cold track)

**Producer:** Ledger Worker (M09) + Restock Service (M12)
**Consumer chính:** Product Service (display cache update)
**Partition key:** `sku_id`

```java
public record StockChangedPayload(
    String productId,
    String skuId,
    long availableQuantity,    // per-SKU aggregate (sum across warehouses)
    String changeType          // ORDER_RESERVED | ORDER_DEDUCTED | ORDER_CANCELLED | RESERVATION_EXPIRED | ADMIN_RESTOCK | ORDER_RETURNED_RESTOCK
) {}
```

### 5.3 ReturnReceivedAllPayload (public, signal)

**Producer:** Restock Service với `reference_type='ORDER_RETURNED_RESTOCK'` (M12)
**Consumer chính:** Order Service (mở bước refund)
**Partition key:** `order_id`

```java
public record ReturnReceivedAllPayload(
    long orderId,
    String refundRequestId,
    List<Long> requiredOrderItemIds,
    List<Long> receivedOrderItemIds,
    boolean allItemsReceived,
    Instant confirmedAt
) {}
```

### 5.4 ShipToCustomerPayload (public, signal)

**Producer:** Ship to Customer API (M12)
**Consumer chính:** Order Service (`order-inventory-ship-consumer`)
**Partition key:** `order_id`

```java
public record ShipToCustomerPayload(
    long orderId,
    long warehouseId,
    Instant shippedAt,
    String courierNote
) {}
```

---

## 6. EventSerializer

> Jackson-based, ISO 8601 UTC, `WRITE_DATES_AS_TIMESTAMPS = false`.

```java
@Component
public final class EventSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private EventSerializer() {}

    public static String toJson(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize event", e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize event", e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
```

---

## 7. CloudEventImpl

```java
package com.platform.inventoryservice.event;

import com.platform.inventoryservice.domain.event.CloudEvent;

import java.time.Instant;
import java.util.UUID;

public record CloudEventImpl<P>(
    UUID eventId,
    String eventType,
    String aggregateType,
    String aggregateId,
    String correlationId,
    Instant timestamp,
    String source,
    int schemaVersion,
    P payload
) implements CloudEvent<P> {

    public static <P> CloudEventImpl<P> create(
        String eventType,
        String aggregateId,
        String correlationId,
        P payload
    ) {
        return new CloudEventImpl<>(
            UUID.randomUUID(),
            eventType,
            SourceConstants.AGGREGATE_TYPE,
            aggregateId,
            correlationId == null ? UUID.randomUUID().toString() : correlationId,
            Instant.now(),
            SourceConstants.SERVICE_SOURCE,
            SourceConstants.SCHEMA_VERSION,
            payload
        );
    }

    public String toJsonString() {
        return EventSerializer.toJson(this);
    }
}
```

> Trong trường hợp hot track (`INVENTORY_STOCK_MUTATION`), `event_id` được generate tại Java layer trước khi gọi Lua (để idempotency tracking xuyên suốt tới Ledger Worker).

---

## 8. InventoryEventBuilder

```java
@Component
@RequiredArgsConstructor
public class InventoryEventBuilder {

    /**
     * Build CloudEvent cho INVENTORY_STOCK_MUTATION (hot track).
     * Được gọi bởi Lua invocation wrapper (M06) để build JSON payload ARGV[4].
     */
    public CloudEventImpl<StockMutationPayload> buildMutation(
        String skuId, String productId, long warehouseId,
        String changeType, int quantity,
        long orderId, long orderItemId,
        String correlationId
    ) {
        StockMutationPayload payload = new StockMutationPayload(
            skuId, productId, warehouseId,
            changeType, quantity,
            0L, 0L,    // availableAfter/reservedAfter sẽ được set sau khi Lua return
            orderId, orderItemId
        );
        // Aggregate_id dùng sku_id cho stock mutation
        return CloudEventImpl.create(
            "INVENTORY_STOCK_MUTATION",
            skuId,
            correlationId,
            payload
        );
    }

    /**
     * Build CloudEvent cho INVENTORY_STOCK_CHANGED (public per-SKU aggregate).
     * Caller đã có sẵn available_quantity từ aggregate query.
     */
    public CloudEventImpl<StockChangedPayload> buildStockChanged(
        String skuId, String productId,
        long availableQuantity, String changeType,
        String correlationId
    ) {
        StockChangedPayload payload = new StockChangedPayload(
            productId, skuId, availableQuantity, changeType
        );
        return CloudEventImpl.create(
            "INVENTORY_STOCK_CHANGED",
            skuId,
            correlationId,
            payload
        );
    }

    /**
     * Build CloudEvent cho INVENTORY_RETURN_RECEIVED_ALL (signal nghiệp vụ).
     */
    public CloudEventImpl<ReturnReceivedAllPayload> buildReturnReceivedAll(
        long orderId, String refundRequestId,
        List<Long> requiredOrderItemIds, List<Long> receivedOrderItemIds,
        Instant confirmedAt,
        String correlationId
    ) {
        ReturnReceivedAllPayload payload = new ReturnReceivedAllPayload(
            orderId, refundRequestId,
            requiredOrderItemIds, receivedOrderItemIds,
            true, confirmedAt
        );
        // Aggregate_id = order_id cho signal events
        return CloudEventImpl.create(
            "INVENTORY_RETURN_RECEIVED_ALL",
            String.valueOf(orderId),
            correlationId,
            payload
        );
    }

    /**
     * Build CloudEvent cho INVENTORY_SHIP_TO_CUSTOMER (signal nghiệp vụ).
     */
    public CloudEventImpl<ShipToCustomerPayload> buildShipToCustomer(
        long orderId, long warehouseId,
        Instant shippedAt, String courierNote,
        String correlationId
    ) {
        ShipToCustomerPayload payload = new ShipToCustomerPayload(
            orderId, warehouseId, shippedAt, courierNote
        );
        return CloudEventImpl.create(
            "INVENTORY_SHIP_TO_CUSTOMER",
            String.valueOf(orderId),
            correlationId,
            payload
        );
    }
}
```

---

## 9. StockMutationPayloadBuilder (for Lua ARGV[4])

```java
@Component
@RequiredArgsConstructor
public class StockMutationPayloadBuilder {

    private final InventoryEventBuilder eventBuilder;

    /**
     * Build JSON payload cho Lua ARGV[4] (event_body của XADD).
     * Đây là JSON thuần (không bọc CloudEvents wrapper) vì Redis Stream chỉ lưu
     * payload nguyên bản; wrapper sẽ được bọc bởi Relay Worker trước khi gửi Kafka.
     */
    public StockMutationPayload forMutation(
        String skuId, String productId, long warehouseId,
        String changeType, int quantity,
        long orderId, long orderItemId,
        String correlationId
    ) {
        return new StockMutationPayload(
            skuId, productId, warehouseId,
            changeType, quantity,
            0L, 0L,                 // availableAfter/reservedAfter: filled post-Lua
            orderId, orderItemId
        );
    }

    /**
     * Build CloudEvents-wrapped JSON cho Relay Worker forward sang Kafka.
     * Caller (Relay Worker) dùng method này sau khi đã nhận stream message.
     */
    public String buildCloudEventJson(
        String skuId, String productId, long warehouseId,
        String changeType, int quantity,
        long availableAfter, long reservedAfter,
        long orderId, long orderItemId,
        String correlationId, UUID eventId
    ) {
        CloudEventImpl<StockMutationPayload> envelope = new CloudEventImpl<>(
            eventId,
            "INVENTORY_STOCK_MUTATION",
            SourceConstants.AGGREGATE_TYPE,
            skuId,
            correlationId,
            Instant.now(),
            SourceConstants.SERVICE_SOURCE,
            SourceConstants.SCHEMA_VERSION,
            new StockMutationPayload(
                skuId, productId, warehouseId,
                changeType, quantity,
                availableAfter, reservedAfter,
                orderId, orderItemId
            )
        );
        return envelope.toJsonString();
    }
}
```

---

## 10. EventDeserializer (cho Ledger Worker consume)

```java
@Component
public class EventDeserializer {

    private final ObjectMapper mapper;

    public EventDeserializer() {
        this.mapper = EventSerializer.mapper();
    }

    @SuppressWarnings("unchecked")
    public CloudEventImpl<JsonNode> deserialize(String json) {
        try {
            // Parse generic CloudEvent với payload là JsonNode để switch theo event_type
            return mapper.readValue(json, new TypeReference<CloudEventImpl<JsonNode>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize CloudEvent", e);
        }
    }

    public <P> P extractPayload(CloudEventImpl<JsonNode> event, Class<P> payloadType) {
        return mapper.convertValue(event.payload(), payloadType);
    }
}
```

---

## 11. Partition Key Resolver

> Mặc dù Debezium CDC tự trích `partition_key` từ cột `inventory_outbox.partition_key` (M08), cho `INVENTORY_STOCK_MUTATION` (do Relay Worker gửi thẳng) cần explicit key.

```java
@Component
public class PartitionKeyResolver {

    public String forStockMutation(StockMutationPayload payload) {
        return payload.skuId();   // per-SKU ordering
    }

    public String forStockChanged(StockChangedPayload payload) {
        return payload.skuId();
    }

    public String forReturnReceivedAll(ReturnReceivedAllPayload payload) {
        return String.valueOf(payload.orderId());   // per-order ordering
    }

    public String forShipToCustomer(ShipToCustomerPayload payload) {
        return String.valueOf(payload.orderId());
    }
}
```

---

## 12. JSON Examples

### 12.1 INVENTORY_STOCK_MUTATION (full envelope)

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174000",
  "event_type": "INVENTORY_STOCK_MUTATION",
  "aggregate_type": "Inventory",
  "aggregate_id": "sku-001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:05:00.123Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "skuId": "sku-001",
    "productId": "prod-uuid-001",
    "warehouseId": 5,
    "changeType": "ORDER_RESERVED",
    "quantity": 2,
    "availableAfter": 798,
    "reservedAfter": 2,
    "orderId": 10001,
    "orderItemId": 2001
  }
}
```

> Jackson default sử dụng field name (camelCase) không phải `snake_case`. Nếu contract matrix yêu cầu snake_case cho consumer compatibility, dùng `@JsonProperty` hoặc `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)` trên record.

### 12.2 INVENTORY_STOCK_CHANGED

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "event_type": "INVENTORY_STOCK_CHANGED",
  "aggregate_type": "Inventory",
  "aggregate_id": "sku-001",
  "correlation_id": "sys-abc",
  "timestamp": "2026-02-28T10:05:01.456Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "productId": "prod-uuid-001",
    "skuId": "sku-001",
    "availableQuantity": 798,
    "changeType": "ORDER_RESERVED"
  }
}
```

### 12.3 INVENTORY_RETURN_RECEIVED_ALL

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174004",
  "event_type": "INVENTORY_RETURN_RECEIVED_ALL",
  "aggregate_type": "Inventory",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T09:58:30Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "orderId": 10001,
    "refundRequestId": "rr-2026-0001",
    "requiredOrderItemIds": [2001, 2002],
    "receivedOrderItemIds": [2001, 2002],
    "allItemsReceived": true,
    "confirmedAt": "2026-03-05T09:58:30Z"
  }
}
```

### 12.4 INVENTORY_SHIP_TO_CUSTOMER

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174005",
  "event_type": "INVENTORY_SHIP_TO_CUSTOMER",
  "aggregate_type": "Inventory",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-02T16:00:00Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "orderId": 10001,
    "warehouseId": 5,
    "shippedAt": "2026-03-02T16:00:00Z",
    "courierNote": "Đã bàn giao cho đơn vị vận chuyển nội bộ"
  }
}
```

---

## 13. Idempotency Considerations

| Field | Idempotent? | Ghi chú |
|:---|:---|:---|
| `event_id` | ✅ UNIQUE | Mỗi event 1 ID; `inventory_ledger.event_id` UNIQUE cho `INVENTORY_STOCK_MUTATION`; `inventory_outbox.event_id` PRIMARY KEY cho public events |
| `(aggregate_id, refund_request_id) WHERE event_type='INVENTORY_RETURN_RECEIVED_ALL'` | ✅ Partial UNIQUE | Chống duplicate emit refund signal |

> Consumer (Product, Order) phải idempotent theo `event_id`.

---

## 14. Schema Versioning

- Hiện tại: tất cả events có `schema_version = 1`.
- Khi thay đổi: bump version → consumer check version trước khi parse.
- Quy tắc: chỉ **additive changes** (thêm field optional). KHÔNG rename, KHÔNG đổi type, KHÔNG xoá field trong cùng version.
- Cross-version compatibility đảm bảo bởi `_contracts/CONTRACT_MATRIX.md`.

---

## 15. Verification

| Check | Cách verify |
|:---|:---|
| JSON format | Unit test so sánh output với examples §12 |
| Timestamp ISO 8601 UTC | Test `Instant.parse(json.get("timestamp"))` |
| Schema version | Test `json.get("schema_version") == 1` |
| Required fields | Validate Jackson `@NotNull` cho mọi wrapper field |
| Round-trip | Build event → serialize → deserialize → equals |
| Partition key | Test resolver return đúng field per event type |

---

## 16. Files Created

```
src/main/java/com/platform/inventoryservice/event/
├── InventoryEventBuilder.java
├── StockMutationPayloadBuilder.java
├── EventSerializer.java
├── EventDeserializer.java
├── PartitionKeyResolver.java
└── SourceConstants.java

(Domain records đã tạo ở M04 — package com.platform.inventoryservice.domain.event)
```