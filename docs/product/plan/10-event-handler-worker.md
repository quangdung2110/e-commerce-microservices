# Module 10: Event Handler Worker

> **Status:** Module 10 of 15
> **Prerequisites:** Module 04 (Redis caching), Module 09 (Kafka/outbox)
> **Dependents:** None (leaf module)

---

## 1. Overview

This module implements the Event Handler Worker — a Kafka consumer that processes events emitted by the Product Service itself (`t.product.events`, `t.category.events`). Its primary responsibilities are: (1) handling `CATEGORY_DELETED` to batch-hide affected products, and (2) refreshing Redis cache for `SKU_CREATED` and `SKU_UPDATED` events.

**Critical rule:** This worker **only reads from the database**. It never writes back to the database.

---

## 2. Kafka Consumer Configuration

### 2.1 File: `config/KafkaConsumerConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/config/KafkaConsumerConfig.java`

- [x] Create `@Configuration` class
- [x] Configure consumer factory for `product-event-handler-group`:
  - Key deserializer: `StringDeserializer`
  - Value deserializer: `StringDeserializer`
  - `auto.offset.reset: earliest`
  - `enable.auto.commit: false` (manual commit after processing)
  - `max.poll.records: 100`
- [x] Configure `ConcurrentKafkaListenerContainerFactory` with concurrency: 3
- [x] Configure `DefaultErrorHandler` with:
  - Retry: 3 attempts, exponential backoff (500ms, 1s, 2s)
  - After retries: `DeadLetterPublishingRecoverer` publishing to `t.product.events.DLQ`
- [x] Register `ContainerProperties.AckMode.MANUAL_IMMEDIATE`

---

## 3. Event Handler Worker

### 3.1 File: `worker/consumer/ProductEventHandlerWorker.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/consumer/ProductEventHandlerWorker.java`

- [x] Create `@Service` class
- [x] Annotate with `@KafkaListener` for topic `t.product.events`, group ID `product-event-handler-group`
- [x] Inject: `ObjectMapper`, `SkuCacheRepository`, `SkuRepository`, `ProductRepository`, `SkuJpaMapper`, `ProductJpaMapper`
- [x] **Consume method**:
  - Deserialize CloudEvents JSON
  - Switch on `eventType`:
    - `CATEGORY_DELETED` → handleCategoryDeleted(event)
    - `SKU_CREATED` → handleSkuCreated(event)
    - `SKU_UPDATED` → handleSkuUpdated(event)
  - Commit offset on success
  - On error: do not commit, let error handler retry/DLQ

### 3.2 File: `worker/consumer/CategoryEventHandlerWorker.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/consumer/CategoryEventHandlerWorker.java`

- [x] Create `@Service` class
- [x] Annotate with `@KafkaListener` for topic `t.category.events`, group ID `product-event-handler-group`
- [x] Handle `CATEGORY_DELETED` event only
- [x] All other category events: log and skip (no action needed)

---

## 4. Event Handling Logic

### 4.1 File: `worker/consumer/handler/CategoryDeletedHandler.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/consumer/handler/CategoryDeletedHandler.java`

- [x] Create `@Component` class
- [x] Method `void handle(CloudEvent event)`:
  - Extract `categoryId` from payload
  - Query database: `SELECT product_id FROM products WHERE category_id = :categoryId AND is_deleted = false AND status = 'ACTIVE'` (batched: 100 per batch)
  - For each batch: UPDATE `products SET status = 'HIDDEN' WHERE product_id IN (...)` — **direct JDBC or JPA batch update**
  - Insert `PRODUCT_STATUS_CHANGED` events for each affected product into outbox
  - **No Redis operations** in this handler (cache update is handled by SKU_UPDATED events emitted from the batch update)
  - Log: number of products hidden, number of batches
- [x] Implement staleness guard: compare `event.timestamp` with product's `updated_at` — skip if product was updated after the event
- [x] Implement idempotency: use `eventId` as idempotency key in Redis

### 4.2 File: `worker/consumer/handler/SkuCreatedHandler.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/consumer/handler/SkuCreatedHandler.java`

- [x] Create `@Component` class
- [x] Method `void handle(CloudEvent event)`:
  - Extract `skuId` from payload
  - Query full SKU snapshot from database (including product info for cache entry)
  - Build `SkuCacheEntry` from domain objects
  - **Staleness guard**: compare `event.created_at` with cached `created_at` — if cache has newer data, skip
  - `Redis JSON.SET product:sku:{skuId}` with TTL 3600s
  - Log: SKU ID, cache write result

### 4.3 File: `worker/consumer/handler/SkuUpdatedHandler.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/consumer/handler/SkuUpdatedHandler.java`

- [x] Create `@Component` class
- [x] Method `void handle(CloudEvent event)`:
  - Extract `skuId`, `changedFields[]`, `updatedAt` from payload
  - Staleness guard based on `changedFields`:
    - Price fields (`original_price`, `promotional_price`, `best_price`, `has_promotion`): compare `event.updatedAt` > `cached.priceUpdatedAt`
    - `sku_status`: compare `event.updatedAt` > `cached.lastInventorySyncAt`
    - Other fields: compare `event.updatedAt` > `cached.updatedAt` (implicit)
  - If any staleness guard fails: log and skip (data is already newer)
  - Query DB for full snapshot (needed to rebuild cache entry)
  - `Redis JSON.SET product:sku:{skuId}` with TTL 3600s
  - Log: SKU ID, changed fields, staleness check result
- [x] Implement `StalenessChecker` helper class with logic for each field type

---

## 5. Idempotency and Error Handling

### 5.1 File: `worker/common/EventIdempotencyGuard.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/common/EventIdempotencyGuard.java`

- [x] Create `@Component` class
- [x] Inject `RedisTemplate<String, String>`
- [x] Method `boolean isNew(String eventId)`: SETNX with 24h TTL
- [x] Method `boolean isProcessed(String eventId)`: EXISTS

### 5.2 File: `worker/common/EventProcessingResult.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/common/EventProcessingResult.java`

- [x] Create record: `success (boolean)`, `skipped (boolean)`, `stale (boolean)`, `errorMessage (String)`
- [x] Factory methods: `success()`, `skipped(String reason)`, `stale(String reason)`, `error(String message)`

### 5.3 File: `worker/common/KafkaConsumerErrorHandler.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/common/KafkaConsumerErrorHandler.java`

- [x] Create `@Component` implementing `ErrorHandler`
- [x] Log error with event metadata (topic, partition, offset, eventId)
- [x] Classify errors: retryable vs. non-retryable
- [x] On non-retryable error: publish to DLQ immediately

---

## 6. Acceptance Criteria

- [x] `CATEGORY_DELETED` handler correctly batch-updates products to HIDDEN (100 per batch)
- [x] `CATEGORY_DELETED` handler does NOT write to Redis (only DB)
- [x] Staleness guard correctly rejects outdated events
- [x] `SKU_CREATED` and `SKU_UPDATED` handlers correctly update Redis cache
- [x] Idempotency guard prevents duplicate processing of the same event
- [x] Kafka offset is committed only after successful processing
- [x] Failed events are retried 3 times before going to DLQ
- [x] Consumer handles malformed JSON gracefully (DLQ, not crash)
