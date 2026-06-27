# Module 12: Inventory Sync Two-Phase

> **Status:** Module 12 of 15
> **Prerequisites:** Module 04 (Redis), Module 06 (business services), Module 10 (event handler)
> **Dependents:** None (leaf module)

---

## 1. Overview

This module implements the **Two-Phase Lock-Free Inventory Sync Architecture** — the most critical performance optimization in the Product Service. It replaces pessimistic locking with a two-phase approach:

- **Phase 1 (Fast Path):** Kafka consumer receives inventory events, executes Lua CAS on Redis, returns in < 5ms with ZERO database locks
- **Phase 2 (Slow Path):** Scheduled worker drains Redis ZSET, batch syncs to PostgreSQL with optimistic locking, triggers FSM auto-hide/reactivate

---

## 2. Architecture Summary

```
Inventory Service
       │
       │ INVENTORY_STOCK_CHANGED (Kafka)
       ▼
┌─────────────────────────────────────────────────┐
│  Phase 1: Fast Path (Kafka Consumer)             │
│  1. Parse Kafka message                           │
│  2. Lua CAS on Redis (staleness guard)           │
│  3. Immediate cache warm (product:sku:{id})     │
│  4. ZADD to pending queue                        │
│  5. Kafka ACK (offset commit) ← < 5ms            │
└─────────────────────────────────────────────────┘
       │
       │ (async, ~500ms later)
       ▼
┌─────────────────────────────────────────────────┐
│  Phase 2: Slow Path (Scheduled Worker)           │
│  1. ZPOPMIN drain pending queue (100/run)       │
│  2. Group by productId                          │
│  3. Per-product: optimistic lock + batch update │
│  4. FSM triggers (auto-hide/reactivate)         │
│  5. Batch outbox insert (SKU_UPDATED)           │
│  6. ZREM ack pending items                      │
└─────────────────────────────────────────────────┘
```

---

## 3. Kafka Consumer Configuration (Phase 1)

### 3.1 File: `config/InventoryKafkaConsumerConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/config/InventoryKafkaConsumerConfig.java`

- [x] Create `@Configuration` class
- [x] Configure consumer factory for `product-inventory-fast-sync-group`:
  - `auto.offset.reset: latest` (we want latest state, not replay)
  - `enable.auto.commit: false`
  - `max.poll.records: 500`
  - `fetch.min.bytes: 1`
  - `fetch.max.wait.ms: 100`
- [x] Configure `ConcurrentKafkaListenerContainerFactory` with concurrency: 3
- [x] Configure `DefaultErrorHandler`: retry 5 times with fixed backoff (200ms), then DLQ
- [x] `AckMode.MANUAL_IMMEDIATE` for precise offset control

---

## 4. Phase 1: Fast Path Consumer

### 4.1 File: `worker/consumer/InventorySyncFastPathConsumer.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/consumer/InventorySyncFastPathConsumer.java`

- [x] Create `@Service` class
- [x] Annotate with `@KafkaListener` for topic `t.inventory.events`, group ID `product-inventory-fast-sync-group`
- [x] Inject: `ObjectMapper`, `InventoryFastSyncLuaExecutor`, `InventorySyncRepository`, `SkuCacheRepository`, `InventorySyncMetrics`
- [x] **Consumer method**:
  ```java
  public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
      try {
          InventoryEvent event = parse(record.value());
          String skuJson = buildMergedSkuJson(...);
          LuaSyncResult result = luaExecutor.execute(
              event.getSkuId(),
              event.getEventTimestamp(),
              event.getAvailableQuantity(),
              event.getProductId(),
              event.deriveSkuStatus(),
              skuJson
          );
          if (result.applied) {
              metrics.recordApplied();
          } else if (result.stale) {
              metrics.recordStale();
          } else if (result.cacheMiss) {
              metrics.recordCacheMiss();
          }
          inventorySyncRepository.addPendingSync(...);
          ack.acknowledge();
      } catch (Exception e) {
          log.error("Failed to process inventory event...", e);
          throw e;
      }
  }
  ```
- [x] Only ACK after Lua script executes successfully (regardless of stale/apply)
- [x] On exception: do NOT ACK (Kafka will redeliver)

### 4.2 File: `worker/consumer/InventoryEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/consumer/InventoryEvent.java`

- [x] Create `@Data` POJO: `skuId`, `productId`, `availableQuantity (int)`, `skuStatus (String)`, `eventTimestamp (long epoch millis)`, `eventId (String)`
- [x] Static `fromCloudEvent(CloudEvent)` factory method (implemented as Jackson `@JsonProperty` getters)
- [x] Validation: `skuId`, `productId`, `eventTimestamp` must not be null

---

## 5. Phase 2: Slow Path Scheduled Worker

### 5.1 File: `worker/scheduler/InventorySyncSlowPathWorker.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/scheduler/InventorySyncSlowPathWorker.java`

- [x] Create `@Component` class
- [x] Inject: `InventorySyncRepository`, `InventorySyncOrchestrator`, `InventorySyncRequeueHandler`, `InventorySyncMetrics`
- [x] Configure `@Scheduled(fixedDelayString = "${app.inventory-sync.interval-ms:500}")` — 500ms interval
- [x] **Scheduled run method**:
  - Drain ZSET: `drainPendingSync(100)` (100 items per run to avoid long transactions)
  - If empty: return immediately
  - Group by `productId`: `Map<String, List<PendingSyncItem>>`
  - For each product group:
    - Call `inventorySyncOrchestrator.syncBatch(Map.Entry)`
    - On success: acknowledge pending items (ZREM)
    - On `OptimisticLockingFailureException`: requeue for next run
  - Metrics: record processed count, batch count, error count

### 5.2 File: `worker/scheduler/InventorySyncMetrics.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/scheduler/InventorySyncMetrics.java`

- [x] Create `@Component` class with Micrometer metrics
- [x] Counters: `inventory.sync.phase1.applied`, `inventory.sync.phase1.stale`, `inventory.sync.phase1.cache_miss`, `inventory.sync.phase2.processed`, `inventory.sync.phase2.errors`, `inventory.sync.phase2.requeued`
- [x] Timer: `inventory.sync.phase2.duration`
- [x] Gauge: `inventory.sync.pending_queue_size`

---

## 6. Phase 2 Orchestration

### 6.1 File: `service/InventorySyncOrchestrator.java`

**Path:** `product-service/src/main/java/com/platform/productservice/service/InventorySyncOrchestrator.java`

**(Reference implementation details — already implemented)**

- [x] Method `void syncBatch(String productId, List<PendingSyncItem> items)`:
  - Start transaction with `READ_COMMITTED` isolation
  - Load product with optimistic lock: `SELECT * FROM products WHERE product_id = :id` (no explicit lock — rely on `@Version`)
  - Load all SKUs for product: `SELECT * FROM product_skus WHERE product_id = :id AND deleted = false`
  - Build `Map<skuId, Sku>` from results
  - For each `PendingSyncItem`:
    - Find matching SKU in map
    - Apply update: only if `item.eventTimestamp > sku.lastInventorySyncAt`
    - If `availableQuantity == 0`: set `skuStatus = OUT_OF_STOCK`
    - Set `sku.displayQuantity`, `sku.lastInventorySyncAt = item.eventTimestamp`
  - Call `FsmTriggerCalculator.calculateFsmTrigger(product, updatedSkus)`:
    - If `AUTO_HIDE_OUT_OF_STOCK`: set `product.status = HIDDEN`, `product.updatedAt = now`
    - If `AUTO_REACTIVATE_IN_STOCK`: set `product.status = ACTIVE`, `product.updatedAt = now`
  - Batch update all SKUs (single UPDATE statement)
  - Batch insert `SKU_UPDATED` outbox events (`changeSource = INVENTORY_SYNC_PHASE2`)
  - Commit transaction
  - **After commit**: acknowledge ZSET pending items

### 6.2 Skip: `service/InventorySyncBatchUpdater.java`

> **Note:** This file is **NOT needed** as a separate class. The `InventorySyncOrchestrator` already implements all batch update logic directly using `skuRepository.save()`, `outboxRepository.saveAll()`, and domain event creation. Keeping the logic in the orchestrator avoids an extra indirection layer.

---

## 7. Consistency Guarantees

### 7.1 Staleness Guard (Multi-Layer)

| Layer | Mechanism | Check |
|---|---|---|
| Phase 1 | Lua CAS | `event_timestamp > cached.last_inventory_sync_at` |
| Phase 1 | Product ID guard | `cached.product_id == event.product_id` |
| Phase 2 | Timestamp comparison | `item.eventTimestamp > sku.lastInventorySyncAt` |
| Event Handler | Outbox event guard | `event.updated_at > search_doc.updated_at` |

### 7.2 No Event Loss

1. Kafka uses at-least-once delivery
2. Phase 1 writes to Redis ZSET **before** acknowledging Kafka offset
3. Phase 2 removes from ZSET **after** database commit
4. If Phase 2 fails: items remain in ZSET, retry on next run

### 7.3 Idempotent Processing

1. Lua CAS ensures same event applied only once (compare-and-swap)
2. Phase 2 staleness guard on `eventTimestamp > lastInventorySyncAt`
3. Event handler outbox guard on `event.updated_at`

---

## 8. Error Handling

### 8.1 File: `worker/scheduler/InventorySyncRequeueHandler.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/scheduler/InventorySyncRequeueHandler.java`

- [x] Method `void requeue(List<PendingSyncItem> items)`: ZADD back to pending queue with incremented retry count
- [x] Method `void requeueWithDelay(List<PendingSyncItem> items, long delayMs)`: use Redis sorted set with score = `now + delay`
- [x] Max retry: 5 attempts, after that send to DLQ
- [x] Track retry count in ZSET member metadata (via `retryCount` field in `PendingSyncItem`)

### 8.2 DLQ Handler

- [x] Dead Letter Queue: `t.inventory.events.DLQ` (via `DefaultErrorHandler` in `InventoryKafkaConsumerConfig`)
- [ ] Consumer for DLQ: manual inspection and replay capability
- [ ] Alert on DLQ messages: metrics counter + alerting rule

---

## 9. Lua Script Reference

### 9.1 File: `resources/lua/inventory_fast_sync.lua`

**Full Lua script implementation:**

```lua
-- KEYS[1] = product:sku:{sku_id}
-- KEYS[2] = product:inventory:pending
-- ARGV[1] = event_timestamp (epoch millis)
-- ARGV[2] = available_quantity
-- ARGV[3] = product_id
-- ARGV[4] = sku_status
-- Returns: 1 = applied, 0 = stale, -1 = cache miss

local cacheKey = KEYS[1]
local pendingZSet = KEYS[2]
local eventTimestamp = tonumber(ARGV[1])
local availableQty = tonumber(ARGV[2])
local productId = ARGV[3]
local skuStatus = ARGV[4]

local existingJson = redis.call('JSON.GET', cacheKey)
if not existingJson then
    local member = cacheKey .. ':' .. eventTimestamp
    redis.call('ZADD', pendingZSet, eventTimestamp, member)
    return -1
end

local data = cjson.decode(existingJson)
local cachedTs = tonumber(data.last_inventory_sync_at) or 0

-- Staleness guard: reject old events
if eventTimestamp <= cachedTs then
    return 0
end

-- Product ID guard: reject events for different products
if data.product_id and data.product_id ~= '' and data.product_id ~= productId then
    return 0
end

-- Apply update
data.display_quantity = availableQty
data.last_inventory_sync_at = eventTimestamp
data.sku_status = (availableQty == 0) and 'OUT_OF_STOCK' or skuStatus
data.is_available = availableQty > 0

redis.call('JSON.SET', cacheKey, '$', cjson.encode(data))

-- ZADD to pending queue for Phase 2
local member = cacheKey .. ':' .. eventTimestamp
redis.call('ZADD', pendingZSet, eventTimestamp, member)
return 1
```

---

## 10. Acceptance Criteria

- [x] Phase 1 completes in < 5ms with ZERO database locks
- [x] Phase 1 Lua script correctly rejects stale events (eventTimestamp <= cachedTs)
- [x] Phase 1 Lua script correctly rejects events for different product IDs
- [x] Phase 1 ZADDs to pending queue before Kafka ACK (no event loss)
- [x] Phase 2 drains pending queue in 100-item batches
- [x] Phase 2 groups items by productId and processes in single transaction
- [x] Phase 2 uses optimistic locking (no FOR UPDATE)
- [x] Phase 2 correctly triggers FSM auto-hide when all SKUs out of stock
- [x] Phase 2 correctly triggers FSM auto-reactivate when any SKU back in stock
- [x] Phase 2 releases ZSET items only after DB commit
- [x] Phase 2 handles OptimisticLockingFailureException by requeuing
- [x] Metrics correctly track applied, stale, cache miss, and error counts
- [ ] DLQ receives events that exceed max retry count
