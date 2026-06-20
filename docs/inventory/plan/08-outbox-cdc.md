# Module 08: Outbox Writer & Debezium CDC

> **Depends on:** M05 (Repository Layer), M07 (Event Definitions)
> **Depended by:** M09 (Ledger Worker), M12 (Admin Services — Restock / Ship / Refund Expectations)

---

## 1. Goal

Triển khai Transactional Outbox Pattern cho 3 public events:

- `INVENTORY_STOCK_CHANGED` (per-SKU aggregate)
- `INVENTORY_RETURN_RECEIVED_ALL` (refund signal)
- `INVENTORY_SHIP_TO_CUSTOMER` (shipping signal)

Đồng thời cấu hình Debezium CDC Connector đọc PostgreSQL WAL → publish Kafka topic `t.inventory.events`.

> Tham chiếu: `inventory_events.md` §4.2.1, §4.3.1, §4.4.1; `inventory_db_design.md` §1.5.

---

## 2. Architecture Recap

```
[Application code]
    │
    │ (cùng PostgreSQL transaction)
    ▼
[UPDATE physical_inventory / inventory_reservations]
    │
    ▼
[INSERT inventory_ledger]
    │
    ▼
[INSERT inventory_outbox]   ← TransactionalOutboxWriter
    │
    ▼
[COMMIT]  →  atomic all-or-nothing
    │
    ▼
[PostgreSQL WAL]
    │
    ▼ (logical replication slot)
[Debezium CDC Connector]
    │
    ▼ (extract partition_key, NOT JSON)
[Kafka topic: t.inventory.events]
```

---

## 3. Package Layout

```
com.platform.inventoryservice.infrastructure.outbox/
└── TransactionalOutboxWriter.java

src/main/resources/debezium/
├── inventory-postgres-connector.json   # connector registration
└── README.md                            # operational notes
```

> Debezium connector config là file JSON (đăng ký qua Kafka Connect REST API). KHÔNG dùng code Java cho connector.

---

## 4. TransactionalOutboxWriter

```java
@Component
@RequiredArgsConstructor
public class TransactionalOutboxWriter {

    private final OutboxRepository outboxRepository;

    /**
     * Insert outbox row trong transaction hiện tại của caller.
     * KHÔNG có @Transactional — phải join với transaction của mutation.
     */
    public void write(OutboxEvent event) {
        outboxRepository.insert(event);
    }

    /**
     * Convenience: build envelope từ CloudEvent rồi insert.
     * Caller vẫn phải ở trong transaction.
     */
    public void writeCloudEvent(CloudEventImpl<?> envelope, String payloadJson) {
        OutboxEvent event = OutboxEvent.of(
            OutboxEventType.valueOf(envelope.eventType()),
            envelope.aggregateId(),
            envelope.aggregateId(),   // default partition_key = aggregate_id cho cold track
            payloadJson,
            null                       // refundRequestId filled by dedicated method
        );
        // Override event_id để dùng envelope's UUID (consistency)
        // (OutboxEvent.of generates new UUID — need to expose setter or factory)
        outboxRepository.insert(event);
    }

    public void writeReturnReceivedAll(
        long orderId, String refundRequestId, String payloadJson
    ) {
        OutboxEvent event = OutboxEvent.of(
            OutboxEventType.INVENTORY_RETURN_RECEIVED_ALL,
            String.valueOf(orderId),
            String.valueOf(orderId),
            payloadJson,
            refundRequestId
        );
        outboxRepository.insert(event);
    }
}
```

> ⚠️ KHÔNG `@Transactional` ở `write(...)`. Caller (RestockService, ShipToCustomerService, LedgerWorker) đã có transaction bao ngoài.

---

## 5. Transaction Discipline cho Caller

Mọi caller sử dụng `TransactionalOutboxWriter` PHẢI tuân thủ pattern:

```java
@Service
@RequiredArgsConstructor
public class RestockService {

    private final PhysicalInventoryRepository physicalRepo;
    private final InventoryLedgerRepository ledgerRepo;
    private final OutboxRepository outboxRepo;
    private final TransactionalOutboxWriter outboxWriter;

    @Transactional
    public RestockResult restock(RestockCommand cmd) {
        // 1. Mutate physical_inventory
        physicalRepo.upsert(cmd.key(), cmd.productId(), cmd.quantity());

        // 2. Append ledger
        InventoryLedgerEntry ledger = InventoryLedgerEntry.append(
            UUID.randomUUID(), cmd.skuId(), cmd.productId(), cmd.warehouseId(),
            cmd.referenceType(), cmd.quantity(),
            cmd.newBalance(), cmd.correlationId()
        );
        ledgerRepo.append(ledger);

        // 3. Emit public event
        CloudEventImpl<StockChangedPayload> envelope = eventBuilder.buildStockChanged(
            cmd.skuId(), cmd.productId(),
            cmd.aggregateAvailable(), cmd.changeType(),
            cmd.correlationId()
        );
        outboxWriter.writeCloudEvent(envelope, envelope.payload().toJsonString());

        // 4. (Optional) Return signal cho RETURN_RECEIVED_ALL — same TX
        if (cmd.referenceType() == ReferenceType.ORDER_RETURNED_RESTOCK) {
            // ... (logic riêng cho refund completion check, xem M12)
        }

        // COMMIT khi method return — atomic với mọi step trên
        return buildResult(ledger, envelope);
    }
}
```

> Nếu step 3 fail → step 1 & 2 cũng rollback. Không có trạng thái "ledger đã ghi nhưng outbox chưa ghi".

---

## 6. Partial Unique Index — Race-safe Refund Signal

DB constraint từ M02:

```sql
CREATE UNIQUE INDEX uq_inventory_outbox_return_received
ON inventory_outbox (aggregate_id, refund_request_id)
WHERE event_type = 'INVENTORY_RETURN_RECEIVED_ALL';
```

Application-level safety (trước khi insert):

```java
@Transactional
public void emitReturnReceivedAllIfComplete(long orderId, String refundRequestId, String correlationId) {
    // 1. Double check
    var existing = outboxRepository.findReturnReceivedAll(String.valueOf(orderId), refundRequestId);
    if (existing.isPresent()) {
        return;  // already emitted
    }

    // 2. Refund completion check
    var snapshot = reservationRepo.checkRefundCompletion(orderId, refundRequestId);
    if (snapshot.pendingCount() > 0) {
        return;  // not all items returned yet
    }

    // 3. Build & insert outbox
    CloudEventImpl<ReturnReceivedAllPayload> envelope = eventBuilder.buildReturnReceivedAll(
        orderId, refundRequestId,
        snapshot.requiredItemIds(), snapshot.receivedItemIds(),
        Instant.now(), correlationId
    );
    outboxWriter.writeReturnReceivedAll(
        orderId, refundRequestId,
        envelope.payload().toJsonString()
    );
    // Partial UNIQUE index catches the race if 2 TX see pending=0 concurrently
}
```

> Partial UNIQUE index là lớp bảo vệ cuối cùng. Application check + DB constraint = double safety.

---

## 7. Debezium CDC Connector Configuration

### 7.1 Connector JSON (đăng ký qua Kafka Connect REST API)

File: `src/main/resources/debezium/inventory-postgres-connector.json`

```json
{
  "name": "inventory-postgres-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "tasks.max": "1",

    "database.hostname": "${postgres_host}",
    "database.port": "${postgres_port}",
    "database.user": "${postgres_user}",
    "database.password": "${postgres_password}",
    "database.dbname": "inventory_db",

    "topic.prefix": "inventory",

    "plugin.name": "pgoutput",
    "publication.autocreate.mode": "disabled",
    "publication.name": "inventory_publication",

    "slot.name": "inventory_slot",
    "publication.enable.auto.commit": "true",

    "table.include.list": "public.inventory_outbox",

    "column.include.list": "public.inventory_outbox.event_id,public.inventory_outbox.event_type,public.inventory_outbox.schema_version,public.inventory_outbox.aggregate_type,public.inventory_outbox.aggregate_id,public.inventory_outbox.partition_key,public.inventory_outbox.refund_request_id,public.inventory_outbox.payload,public.inventory_outbox.created_at",

    "tombstones.on.delete": "false",

    "transforms": "routeTopic,extractKey",
    "transforms.routeTopic.type": "io.debezium.transforms.ByLogicalTableRouter",
    "transforms.routeTopic.topic.regex": "inventory.public.inventory_outbox",
    "transforms.routeTopic.topic.replacement": "t.inventory.events",

    "transforms.extractKey.type": "org.apache.kafka.connect.transforms.ExtractField$Key",
    "transforms.extractKey.field": "partition_key",

    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "false",

    "decimal.handling.mode": "double",
    "time.precision.mode": "connect",

    "snapshot.mode": "never",

    "heartbeat.interval.ms": "10000",
    "max.queue.size.in.bytes": "104857600"
  }
}
```

> ⚠️ KHÔNG parse JSON payload để lấy `partition_key`. SMT `ExtractField` trích cột `partition_key` làm Kafka message key.

### 7.2 Publication setup (PostgreSQL)

```sql
-- Tạo publication cho bảng inventory_outbox
CREATE PUBLICATION inventory_publication FOR TABLE public.inventory_outbox;

-- Verify
SELECT * FROM pg_publication WHERE pubname = 'inventory_publication';
```

### 7.3 Logical Replication Slot

```sql
-- Tạo slot (Debezium sẽ tự tạo nếu quyền đủ, hoặc DBA tạo sẵn)
SELECT * FROM pg_create_logical_replication_slot('inventory_slot', 'pgoutput');
```

### 7.4 Kafka Connect REST Registration

```bash
curl -X POST http://kafka-connect:8083/connectors \
  -H "Content-Type: application/json" \
  -d @inventory-postgres-connector.json

# Verify
curl http://kafka-connect:8083/connectors/inventory-postgres-connector/status
```

---

## 8. CDC → Kafka Message Format

Sau khi Debezium + SMT áp dụng:

| Aspect | Value |
|:---|:---|
| Kafka topic | `t.inventory.events` |
| Kafka key (string) | `partition_key` column value (sku_id hoặc order_id) |
| Kafka value (JSON) | Full row JSON (event_id, event_type, schema_version, aggregate_type, aggregate_id, partition_key, refund_request_id, payload, created_at) |

> ⚠️ **Vấn đề:** Default Debezium value là **full row snapshot**, không phải event payload. Cần SMT thứ 2 để extract `payload` column làm message value.

### 8.1 SMT bổ sung — Extract Payload as Value

```json
{
  "transforms": "routeTopic,extractKey,extractValue",
  "transforms.extractValue.type": "org.apache.kafka.connect.transforms.ExtractField$Value",
  "transforms.extractValue.field": "payload"
}
```

> Sau SMT này, Kafka value = JSON string từ cột `payload` (đã là JSON CloudEvent envelope).

### 8.2 Debezium Headers

| Header | Value |
|:---|:---|
| `__debezium-connector` | `inventory-postgres-connector` |
| `__debezium-topic` | `t.inventory.events` |
| `__debezium-offset` | Source offset (LSN) |

> Consumer có thể dùng offset để debug / track replay.

---

## 9. Outbox Cleanup Coordination

> Module M14 chi tiết, ở đây chỉ note operational coupling:

- Debezium CDC lag thường < 1 phút trong steady state.
- Cleanup Job xóa `inventory_outbox` rows với `created_at < NOW() - 24h` (watermark an toàn).
- Nếu CDC lag tăng bất thường → cleanup sẽ bỏ qua row cũ hơn 7 ngày (hard cap).

---

## 10. Backpressure & Failure Modes

| Failure | Impact | Mitigation |
|:---|:---|:---|
| Debezium down | Outbox rows accumulate; không mất event | Cleanup job giảm batch size; alert khi `countOlderThan(watermark)` > threshold |
| PostgreSQL WAL full | Replication slot bị reject; database dừng | Monitor WAL usage; reserve ≥ 1GB WAL |
| Kafka down | Debezium retry với exponential backoff | Debezium `errors.tolerance=all` + DLQ topic cho malformed messages |
| Application crash sau mutation nhưng trước COMMIT | Mất cả mutation + outbox (rollback) | OK — đảm bảo atomicity |
| Application crash sau COMMIT nhưng trướk Kafka publish | Outbox row tồn tại, chưa publish | OK — Debezium sẽ pick up khi restart |

---

## 11. Monitoring Metrics

| Metric | Type | Labels |
|:---|:---|:---|
| `inventory_outbox_insert_total` | Counter | `event_type` |
| `inventory_outbox_pending` | Gauge (cứ 5s) | — |
| `inventory_outbox_oldest_age_seconds` | Gauge | — |
| `inventory_cdc_lag_seconds` | Gauge (từ Debezium JMX) | — |
| `inventory_cdc_publish_total` | Counter | `event_type` |
| `inventory_cdc_publish_errors_total` | Counter | `event_type`, `error_type` |

> Gauge `inventory_outbox_pending` = `SELECT COUNT(*) FROM inventory_outbox` mỗi 5s (cheap query với partial index).

---

## 12. Verification

| Check | Cách verify |
|:---|:---|
| Outbox insert atomic | Unit test: throw exception giữa chừng → ledger + outbox cùng rollback |
| Partial unique catch | Test: 2 thread cùng insert RETURN_RECEIVED_ALL → 1 success, 1 fail với `constraint violation` |
| Debezium register | `curl /connectors/inventory-postgres-connector/status` → `RUNNING` |
| CDC publish | Insert outbox row → check Kafka topic nhận được message trong < 5s |
| Partition key | `kafka-console-consumer --property print.key=true` → message key đúng sku_id/order_id |
| Cleanup coordination | Cleanup job không xóa row còn chưa được CDC đọc |

---

## 13. Files Created

```
src/main/java/com/platform/inventoryservice/infrastructure/outbox/
└── TransactionalOutboxWriter.java

src/main/resources/debezium/
├── inventory-postgres-connector.json
└── README.md

(Repository OutboxRepositoryImpl đã tạo ở M05)
```