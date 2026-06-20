# Module 09: Relay Worker & Ledger Worker

> **Depends on:** M05 (Repository), M06 (Redis Atomic Ops), M07 (Events), M08 (Outbox)
> **Depended by:** (none — leaves the service)

---

## 1. Goal

Triển khai 2 workers chuyển tiếp event giữa các layer:

- **Redis Stream Relay Worker**: đọc `inventory:stream:{sku:wh}:events` → produce Kafka `t.inventory.events` (event `INVENTORY_STOCK_MUTATION`).
- **Kafka Ledger Worker**: consume `INVENTORY_STOCK_MUTATION` từ Kafka → ghi `inventory_ledger` + cập nhật `inventory_reservations` + insert `inventory_outbox` cho `INVENTORY_STOCK_CHANGED`.

> Tham chiếu: `inventory_workers.md` §1, §2; `inventory_workflow.md` Luồng 1, 2, 3.

---

## 2. Package Layout

```
com.platform.inventoryservice.worker/
├── relay/
│   ├── RedisStreamRelayWorker.java
│   └── StreamMessageParser.java
└── ledger/
    ├── KafkaLedgerWorker.java
    ├── MutationEventProcessor.java
    └── LedgerWriter.java
```

---

## 3. Redis Stream Relay Worker

### 3.1 Sứ mệnh

Là cầu nối giữa Redis Lua scripts (XADD) và Kafka. Worker scan toàn bộ Redis Streams theo pattern `inventory:stream:{*}:events`, đọc entries, build CloudEvent envelope, produce sang Kafka.

### 3.2 Implementation

```java
@Component
@RequiredArgsConstructor
public class RedisStreamRelayWorker {

    private static final String CONSUMER_GROUP = "inventory-stream-relay";
    private static final String CONSUMER_NAME = "relay-" + UUID.randomUUID();
    private static final Duration BLOCK_DURATION = Duration.ofSeconds(1);
    private static final int MAXLEN_APPROX = 100_000;   // soft cap per stream

    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafka;
    private final InventoryProperties props;
    private final InventoryMetrics metrics;

    @PostConstruct
    void ensureConsumerGroup() {
        // Scan all streams & ensure group exists
        // (Implementation: SCAN MATCH inventory:stream:{*}:events + XGROUP CREATE)
    }

    @Scheduled(fixedDelay = 100)   // poll every 100ms
    public void pollAndForward() {
        Set<String> streamKeys = scanAllStreamKeys();
        for (String streamKey : streamKeys) {
            try {
                processStream(streamKey);
            } catch (Exception e) {
                metrics.recordRelayError(streamKey, e.getClass().getSimpleName());
                log.error("Relay error for {}", streamKey, e);
                // Don't propagate — retry next tick
            }
        }
    }

    private void processStream(String streamKey) {
        // XREADGROUP with COUNT 100, BLOCK 1s
        // Read entries from multiple streams if needed (use > syntax)
        @SuppressWarnings("unchecked")
        List<StreamMessage<String, Object>> messages = (List<StreamMessage<String, Object>>)
            (List<?>) redis.execute((RedisCallback<List<StreamMessage<String, Object>>>) connection ->
                connection.streamCommands().xReadGroup(
                    Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                    StreamReadOptions.empty().count(100).block(BLOCK_DURATION),
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed())
                ));

        if (messages == null) return;
        for (StreamMessage<String, Object> msg : messages) {
            forwardToKafka(streamKey, msg);
            ack(streamKey, msg.getId());
        }
    }

    private void forwardToKafka(String streamKey, StreamMessage<String, Object> msg) {
        StreamMessageParser parser = new StreamMessageParser(msg);
        StreamMessageParser.Parsed parsed = parser.parse();

        // Build CloudEvent envelope từ Lua ARGV[4] payload + stream metadata
        String cloudEventJson = parsed.buildCloudEventJson(
            Instant.ofEpochMilli(msg.getTimestamp())
        );

        kafka.send(
            props.getKafka().getTopicEvents(),
            parsed.partitionKey(),     // sku_id
            cloudEventJson
        ).whenComplete((result, ex) -> {
            if (ex != null) {
                metrics.recordKafkaPublishError("STOCK_MUTATION", ex.getClass().getSimpleName());
                throw new RuntimeException(ex);
            }
            metrics.recordKafkaPublish("STOCK_MUTATION");
        });

        // Soft-cap stream length (best-effort)
        redis.opsForStream().trim(streamKey, MAXLEN_APPROX, true);
    }

    private void ack(String streamKey, RecordId msgId) {
        redis.opsForStream().acknowledge(streamKey, CONSUMER_GROUP, msgId);
    }

    private Set<String> scanAllStreamKeys() {
        Set<String> keys = new HashSet<>();
        // SCAN MATCH inventory:stream:{*}:events COUNT 100
        try (Cursor<byte[]> cursor = redis.execute((RedisCallback<Cursor<byte[]>>) connection ->
            connection.scan(ScanOptions.scanOptions()
                .match("inventory:stream:{*}:events")
                .count(100)
                .build()))) {
            while (cursor.hasNext()) {
                keys.add(new String(cursor.next()));
            }
        }
        return keys;
    }
}
```

### 3.3 StreamMessageParser

> Parse Lua ARGV[4] JSON từ Redis Stream entry.

```java
public class StreamMessageParser {

    private final StreamMessage<String, Object> msg;

    public StreamMessageParser(StreamMessage<String, Object> msg) {
        this.msg = msg;
    }

    public Parsed parse() {
        Map<String, Object> body = msg.getBody();
        String eventType = (String) body.get("event_type");
        String changeType = (String) body.get("change_type");
        String payloadJson = (String) body.get("payload");

        StockMutationPayload payload = EventSerializer.fromJson(payloadJson, StockMutationPayload.class);
        String eventId = UUID.randomUUID().toString();  // M07: envelope level event_id

        return new Parsed(eventId, payload, changeType);
    }

    public record Parsed(
        String eventId,
        StockMutationPayload payload,
        String changeType
    ) {
        public String partitionKey() {
            return payload.skuId();
        }

        public String buildCloudEventJson(Instant timestamp) {
            CloudEventImpl<StockMutationPayload> envelope = new CloudEventImpl<>(
                UUID.fromString(eventId),
                "INVENTORY_STOCK_MUTATION",
                SourceConstants.AGGREGATE_TYPE,
                payload.skuId(),
                UUID.randomUUID().toString(),     // correlation — caller passes if available
                timestamp,
                SourceConstants.SERVICE_SOURCE,
                SourceConstants.SCHEMA_VERSION,
                payload
            );
            return envelope.toJsonString();
        }
    }
}
```

> ⚠️ Relay Worker **không** có correlation_id gốc (Lua script chỉ nhận correlation qua ARGV[4] payload). Correlation_id ở envelope là generation mới — Ledger Worker nên dùng `event_id` để dedup thay vì correlation.

---

## 4. Kafka Ledger Worker

### 4.1 Sứ mệnh

Consume `INVENTORY_STOCK_MUTATION` từ Kafka → ghi ledger + cập nhật reservation + emit outbox `INVENTORY_STOCK_CHANGED` (cùng transaction).

### 4.2 Implementation

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaLedgerWorker {

    private final KafkaLedgerConsumer consumer;
    private final MutationEventProcessor processor;
    private final InventoryMetrics metrics;

    @PostConstruct
    void start() {
        consumer.start();
    }

    @PreDestroy
    void stop() {
        consumer.stop();
    }
}
```

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaLedgerConsumer {

    private final KafkaConsumer<String, String> kafkaConsumer;
    private final InventoryProperties props;
    private final MutationEventProcessor processor;
    private volatile boolean running = true;

    @PostConstruct
    void init() {
        // Subscribe & manual poll loop in background thread
        Thread t = new Thread(this::pollLoop, "ledger-worker-" + UUID.randomUUID());
        t.setDaemon(true);
        t.start();
    }

    private void pollLoop() {
        kafkaConsumer.subscribe(List.of(props.getKafka().getTopicEvents()));
        while (running) {
            try {
                ConsumerRecords<String, String> records = kafkaConsumer.poll(
                    Duration.ofMillis(props.getKafka().getPollIntervalMs()));
                if (records.isEmpty()) continue;

                processBatch(records);
                kafkaConsumer.commitSync();
            } catch (WakeupException e) {
                break;   // graceful shutdown
            } catch (Exception e) {
                log.error("Ledger worker poll error", e);
                // Don't commit — retry next poll
            }
        }
        kafkaConsumer.close();
    }

    private void processBatch(ConsumerRecords<String, String> records) {
        // Group by sku_id for batch processing
        Map<String, List<ConsumerRecord<String, String>>> grouped =
            records.stream().collect(groupingBy(ConsumerRecord::key));

        for (var entry : grouped.entrySet()) {
            try {
                processor.processBatch(entry.getValue());
            } catch (Exception e) {
                log.error("Batch processing failed for sku {}", entry.getKey(), e);
                throw e;   // don't commit; retry
            }
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        kafkaConsumer.wakeup();
    }
}
```

### 4.3 MutationEventProcessor

> Core logic: filter `INVENTORY_STOCK_MUTATION` events, dedup, write ledger + reservations + outbox trong cùng transaction.

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class MutationEventProcessor {

    private final EventDeserializer deserializer;
    private final InventoryLedgerRepository ledgerRepo;
    private final InventoryReservationRepository reservationRepo;
    private final PhysicalInventoryRepository physicalRepo;
    private final TransactionalOutboxWriter outboxWriter;
    private final InventoryEventBuilder eventBuilder;
    private final InventoryMetrics metrics;

    @Transactional
    public void processBatch(List<ConsumerRecord<String, String>> records) {
        List<MutationCommand> commands = new ArrayList<>();

        for (ConsumerRecord<String, String> record : records) {
            try {
                CloudEventImpl<JsonNode> envelope = deserializer.deserialize(record.value());

                // Filter: chỉ xử lý INVENTORY_STOCK_MUTATION
                if (!"INVENTORY_STOCK_MUTATION".equals(envelope.eventType())) {
                    log.debug("Skipping non-mutation event: {}", envelope.eventType());
                    continue;
                }

                StockMutationPayload payload = deserializer.extractPayload(
                    envelope, StockMutationPayload.class);

                // Dedup: inventory_ledger.event_id UNIQUE
                if (ledgerRepo.existsByEventId(envelope.eventId())) {
                    log.debug("Duplicate event {}, skipping", envelope.eventId());
                    continue;
                }

                commands.add(new MutationCommand(envelope, payload));
            } catch (Exception e) {
                log.error("Failed to parse event at offset {}", record.offset(), e);
                throw e;   // không commit → retry
            }
        }

        if (commands.isEmpty()) return;

        // Sort by sku_id + event_id for deterministic balance calculation
        commands.sort(Comparator
            .comparing((MutationCommand c) -> c.payload.skuId())
            .thenComparing(c -> c.envelope.eventId().toString()));

        // Group by sku_id for batch physical_inventory update
        Map<String, List<MutationCommand>> bySku = commands.stream()
            .collect(groupingBy(c -> c.payload.skuId()));

        for (var skuEntry : bySku.entrySet()) {
            processSkuGroup(skuEntry.getKey(), skuEntry.getValue());
        }
    }

    private void processSkuGroup(String skuId, List<MutationCommand> cmds) {
        for (MutationCommand cmd : cmds) {
            StockMutationPayload p = cmd.payload;
            ReservationStatus newStatus = mapChangeTypeToReservationStatus(p.changeType());

            // 1. Append ledger
            ReferenceType refType = mapChangeTypeToReferenceType(p.changeType());
            InventoryLedgerEntry ledger = InventoryLedgerEntry.append(
                cmd.envelope.eventId(),
                p.skuId(), p.productId(), p.warehouseId(),
                refType,
                0,                                   // reserve/cancel/deduct/release đều không ghi quantity_change (chỉ reserve/deduct track actual)
                0,                                   // balance_after — query từ physical_inventory
                cmd.envelope.correlationId()
            );
            // ⚠️ Thực tế cần query balance_after từ physical_inventory.post-update
            // Logic xem §4.4
            ledgerRepo.append(ledger);

            // 2. Update reservation (status transition)
            // Dùng idempotency_key từ cmd để lookup
            // ...

            // 3. Update physical_inventory.reserved_quantity hoặc actual_quantity
            // ...

            // 4. Insert outbox INVENTORY_STOCK_CHANGED (aggregate per-SKU)
            // ...

            metrics.recordMutationProcessed(p.changeType());
        }
    }

    // helpers
    private ReservationStatus mapChangeTypeToReservationStatus(String changeType) { ... }
    private ReferenceType mapChangeTypeToReferenceType(String changeType) { ... }

    record MutationCommand(CloudEventImpl<?> envelope, StockMutationPayload payload) {}
}
```

### 4.4 balance_after & actual_quantity Update

> Ledger chỉ ghi nhận biến động physical. Đối với ORDER_RESERVED / ORDER_CANCELLED / RESERVATION_EXPIRED: `actual_quantity` không đổi, chỉ `reserved_quantity` đổi. Đối với ORDER_DEDUCTED: cả `actual_quantity` lẫn `reserved_quantity` đều giảm.

```java
private void applyMutationToPhysical(StockMutationPayload p) {
    SkuWarehouseKey key = new SkuWarehouseKey(p.skuId(), p.warehouseId());

    switch (p.changeType()) {
        case "ORDER_RESERVED" -> {
            // reserved += quantity, actual unchanged
            physicalRepo.adjustReserved(key, +p.quantity());
        }
        case "ORDER_DEDUCTED" -> {
            // reserved -= quantity, actual -= quantity
            physicalRepo.applyDeduct(key, p.quantity());
            // (Impl: query current actual, subtract, UPDATE)
        }
        case "ORDER_CANCELLED", "RESERVATION_EXPIRED" -> {
            // reserved -= quantity, actual unchanged
            physicalRepo.adjustReserved(key, -p.quantity());
        }
    }
}
```

> `physicalRepo.applyDeduct(key, qty)` phải query current `actual_quantity` từ DB (do Lua đã mutate Redis nhưng chưa mutate DB), tính `new_actual = current - qty`, validate `new_actual >= 0`, rồi UPDATE.

### 4.5 Reservation Update

```java
private void updateReservationForMutation(CloudEventImpl<?> envelope, StockMutationPayload p) {
    // Reconstruct idempotency_key from payload
    String op = switch (p.changeType()) {
        case "ORDER_RESERVED"  -> "reserve";
        case "ORDER_DEDUCTED"  -> "deduct";
        default                -> "release";
    };
    String idempotencyKey = op + ":" + p.skuId() + ":" + p.warehouseId() + ":" + p.orderId();

    Optional<InventoryReservation> existing = reservationRepo.findByIdempotencyKey(idempotencyKey);
    if (existing.isEmpty()) {
        log.warn("Reservation not found for idempotency_key={}, skipping", idempotencyKey);
        return;   // Lua mutation đã thành công nhưng DB row chưa có — coi như anomaly
    }

    ReservationStatus newStatus = switch (p.changeType()) {
        case "ORDER_RESERVED"                -> ReservationStatus.RESERVED;
        case "ORDER_DEDUCTED"                -> ReservationStatus.DEDUCTED;
        case "ORDER_CANCELLED", "RESERVATION_EXPIRED" -> ReservationStatus.CANCELLED;
        default -> throw new IllegalStateException("Unknown changeType: " + p.changeType());
    };
    reservationRepo.updateStatus(existing.get().getId(), newStatus);
}
```

> ⚠️ Cold path: Reservation row INSERT thực hiện **trong cùng transaction** với ledger/outbox. Đây là nơi `idempotency_key` từ Redis được copy vào DB column.

### 4.6 Outbox Emit (per-SKU Aggregate)

```java
private void emitStockChangedOutbox(CloudEventImpl<?> envelope, StockMutationPayload p) {
    // Aggregate per-SKU available từ DB (post-mutation)
    long aggregateAvailable = physicalRepo.sumAvailableBySkuId(p.skuId());

    CloudEventImpl<StockChangedPayload> stockChangedEvent = eventBuilder.buildStockChanged(
        p.skuId(), p.productId(),
        aggregateAvailable,
        mapChangeTypeToStockChangedType(p.changeType()),
        envelope.correlationId()
    );

    outboxWriter.write(stockChangedEvent);
}

private String mapChangeTypeToStockChangedType(String mutationChangeType) {
    return switch (mutationChangeType) {
        case "ORDER_RESERVED" -> "ORDER_RESERVED";
        case "ORDER_DEDUCTED" -> "ORDER_DEDUCTED";
        case "ORDER_CANCELLED" -> "ORDER_CANCELLED";
        case "RESERVATION_EXPIRED" -> "RESERVATION_EXPIRED";
        default -> throw new IllegalStateException("Unknown: " + mutationChangeType);
    };
}
```

---

## 5. Worker Lifecycle Management

### 5.1 Startup Order

```
1. Spring context init
2. JPA / Hibernate validate schema
3. Redis connection pool init
4. Kafka producer init
5. RedisStreamRelayWorker @PostConstruct → SCAN streams & XGROUP CREATE
6. KafkaLedgerWorker @PostConstruct → start polling thread
7. App ready (health UP)
```

### 5.2 Graceful Shutdown

```
1. SIGTERM received
2. Health endpoint → DOWN (start draining)
3. Relay Worker: stop accepting new XREADGROUP; finish in-flight
4. Ledger Worker: stop poll; commit offsets
5. Kafka producer flush
6. Redis connection pool close
7. Process exit
```

> Spring Boot graceful shutdown đã handle servlet draining; worker threads cần manual `@PreDestroy` để wakeup consumer.

---

## 6. Concurrency Considerations

| Concern | Approach |
|:---|:---|
| Multi-partition consumer | Spring Kafka `@KafkaListener` với `concurrency = 3` (1 listener / partition) |
| Per-partition ordering | Kafka key = `sku_id` đảm bảo same-SKU events vào cùng partition |
| Idempotency | `inventory_ledger.event_id` UNIQUE catch duplicates |
| Backpressure | `max.poll.records = 500`, `session.timeout.ms = 45000` |
| Poison message | Skip + log; không fail batch; metric alert |

---

## 7. Error Handling

| Failure | Action | Recovery |
|:---|:---|:---|
| Kafka consume fail | Log + skip (poison message) | Alert + manual reprocess |
| Postgres write fail | Throw → no commit → retry next poll | Auto-retry |
| Outbox write fail | Throw → same TX rollback → retry | Auto-retry |
| Serialization fail | Skip + log | Alert |

---

## 8. Metrics (chi tiết tại M15)

| Metric | Type | Labels |
|:---|:---|:---|
| `inventory_relay_poll_total` | Counter | — |
| `inventory_relay_forwarded_total` | Counter | `event_type` |
| `inventory_relay_xack_total` | Counter | — |
| `inventory_relay_error_total` | Counter | `error_type` |
| `inventory_ledger_consumed_total` | Counter | `event_type` |
| `inventory_ledger_written_total` | Counter | `reference_type` |
| `inventory_ledger_dedup_skipped_total` | Counter | — |
| `inventory_ledger_process_duration_seconds` | Timer | — |
| `inventory_ledger_error_total` | Counter | `error_type` |

---

## 9. Testing

### 9.1 Relay Worker

| Test type | Tool | Mục đích |
|:---|:---|:---|
| Unit | JUnit 5 + Mockito | StreamMessageParser logic |
| Integration | `@SpringBootTest` + TestContainers Redis Cluster | XADD → poll → Kafka publish |
| Concurrency | Multi-thread SCAN + XREADGROUP | Verify no race |

### 9.2 Ledger Worker

| Test type | Tool | Mục đích |
|:---|:---|:---|
| Unit | JUnit 5 + Mockito | MutationEventProcessor logic |
| Integration | `@SpringBootTest` + TestContainers (Redis + Kafka + Postgres) | End-to-end: Lua → Stream → Kafka → Ledger Worker → DB |
| Dedup | Produce 2 event cùng `event_id` | Verify 1 ledger row only |
| Outbox | Verify outbox row inserted cùng TX | Rollback test |

---

## 10. Verification

| Check | Cách verify |
|:---|:---|
| Relay polls streams | Log `Relay poll: 5 streams, 23 messages forwarded` |
| Relay forwards to Kafka | `kafka-console-consumer --topic t.inventory.events` thấy event JSON |
| XACK after Kafka ack | Stop Kafka → relay retries next poll |
| Ledger consumes | Log `Ledger batch processed: 10 events, 8 mutations, 2 skipped` |
| Dedup | Send same event_id 2 lần → 1 ledger row |
| Outbox emit | Each mutation → 1 outbox row INVENTORY_STOCK_CHANGED |

---

## 11. Files Created

```
src/main/java/com/platform/inventoryservice/worker/
├── relay/
│   ├── RedisStreamRelayWorker.java
│   └── StreamMessageParser.java
└── ledger/
    ├── KafkaLedgerWorker.java
    ├── KafkaLedgerConsumer.java   (poll loop wrapper)
    ├── MutationEventProcessor.java
    └── LedgerWriter.java
```