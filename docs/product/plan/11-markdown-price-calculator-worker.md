# Module 11: Markdown Price Calculator Worker

> **Status:** Module 11 of 15
> **Prerequisites:** Module 04 (Redis), Module 06 (business services)
> **Dependents:** None (leaf module)

---

## 1. Overview

This module implements the Markdown Price Calculator Worker — a Kafka consumer that processes markdown events (`MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED`) from `t.promotion.markdown.events` topic, calculates promotional prices, and updates the database.

---

## 2. Kafka Consumer Configuration

### 2.1 File: `config/MarkdownKafkaConsumerConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/config/MarkdownKafkaConsumerConfig.java`

- [ ] Create `@Configuration` class
- [ ] Configure consumer factory for `product-markdown-group`:
  - `auto.offset.reset: earliest`
  - `enable.auto.commit: false`
  - `max.poll.records: 50`
- [ ] Configure `ConcurrentKafkaListenerContainerFactory` with concurrency: 2
- [ ] Configure `DefaultErrorHandler`: retry 3 times, exponential backoff (1s, 2s, 4s), then DLQ

---

## 3. Markdown Event Consumer

### 3.1 File: `worker/consumer/MarkdownWorker.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/consumer/MarkdownWorker.java`

- [ ] Create `@Service` class
- [ ] Annotate with `@KafkaListener` for topic `t.promotion.markdown.events`, group ID `product-markdown-group`
- [ ] **Event types handled**: `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED`
- [ ] Inject: `ObjectMapper`, `MarkdownPriceCalculatorProcessor`, `MarkdownStartGateProcessor`
- [ ] **Consumer logic**:
  - Deserialize CloudEvents JSON
  - Extract `eventType`, `payload`
  - Check `start_time` gate: if `now < payload.start_time`, delegate to `MarkdownStartGateProcessor` and ACK immediately
  - Otherwise: delegate to `MarkdownPriceCalculatorProcessor` and process synchronously
  - Commit offset on success

---

## 4. Markdown Price Calculator Processor

### 4.1 File: `worker/processor/MarkdownPriceCalculatorProcessor.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/processor/MarkdownPriceCalculatorProcessor.java`

- [ ] Create `@Component` class
- [ ] Inject: `MarkdownService`, `DistributedLockRepository`, `SkuCacheRepository`, `ProductRepository`, `SkuRepository`, `MarkdownRepository`, `OutboxRepository`, `MarkdownCalculator`, `OutboxService`
- [ ] Method `void processCreated(CloudEvent event)`:
  - Extract payload: `ruleId`, `scopeType` (CATEGORY or PRODUCT), `scopeId`, `discountType`, `discountValue`, `maxDiscountAmount`, `startTime`, `expiresAt`
  - Determine affected SKU IDs: query by `scopeType`:
    - CATEGORY: `SELECT sku_id FROM product_skus ps JOIN products p ON ps.product_id = p.product_id WHERE p.category_id = :categoryId AND ps.deleted = false`
    - PRODUCT: `SELECT sku_id FROM product_skus WHERE product_id = :productId AND deleted = false`
  - Group SKU IDs by `productId`
  - **Per product transaction**:
    - Acquire distributed lock: `product:lock:markdown:{productId}` (TTL: 30s)
    - Lock Product `FOR NO KEY UPDATE`
    - Lock all SKUs `FOR UPDATE`
    - Calculate `promotionalPrice` for each SKU using `MarkdownCalculator`
    - Update `product_markdown_applied` table (INSERT or UPDATE)
    - Update SKUs with new `promotionalPrice`
    - Insert `SKU_UPDATED` outbox events with `changeSource = MARKDOWN_CREATED`
    - Set Redis `product:markdown:active:{skuId}` with TTL = `expiresAt - now`
    - Release lock
    - Log: ruleId, number of SKUs updated
- [ ] Method `void processUpdated(CloudEvent event)`:
  - Query `product_markdown_applied WHERE rule_id = :ruleId`
  - Recalculate all affected SKUs (same as CREATED but with existing rule data)
- [ ] Method `void processEnded(CloudEvent event)`:
  - Query `product_markdown_applied WHERE rule_id = :ruleId`
  - If no records found: log and ACK (no-op idempotent)
  - Reset `promotionalPrice = NULL` on all affected SKUs
  - Delete from `product_markdown_applied`
  - Insert `SKU_UPDATED` outbox events with `changeSource = MARKDOWN_ENDED`
  - Delete Redis `product:markdown:active:{skuId}` entries
- [ ] Error handling: release lock in `finally` block, requeue on lock acquisition failure

---

## 5. Start-Time Gate Processor

### 5.1 File: `worker/processor/MarkdownStartGateProcessor.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/processor/MarkdownStartGateProcessor.java`

- [ ] Create `@Component` class
- [ ] Inject: `RedisKeyBuilder`, `RedisTemplate<String, String>`
- [ ] Method `void scheduleForLater(CloudEvent event)`:
  - Parse `startTime` from payload
  - Calculate delay = `startTime - now`
  - If delay > 0 and delay < 7 days: store in Redis ZSET `product:markdown:gate:{ruleId}` with score = `startTime`
  - If delay > 7 days: do nothing (will be processed by scheduled scanner)
- [ ] Method `void processScheduledEvents()`:
  - Scheduled job runs every minute
  - `ZRANGEBYSCORE product:markdown:gate:* -inf {now}`
  - For each event: process immediately (same as direct event)
  - Remove from ZSET after processing

### 5.2 File: `worker/scheduler/MarkdownGateScannerJob.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/scheduler/MarkdownGateScannerJob.java`

- [ ] Create `@Component` class
- [ ] Cron: `* * * * * *` (every minute)
- [ ] Inject: `MarkdownStartGateProcessor`, `DistributedLockRunner`
- [ ] Acquire distributed lock: `product:lock:markdown-gate-scanner`
- [ ] Call `processScheduledEvents()`
- [ ] Log: number of events processed

---

## 6. Markdown Calculation Formula

The calculation is implemented in `domain/service/MarkdownCalculator.java` (Module 02). This section documents the formulas used by the worker:

**PERCENTAGE discount:**
```
discount_amount = MIN(original_price * discount_value / 100, max_discount_amount)
promotional_price = MAX(0, ROUND(original_price - discount_amount, 2))
```

**FIXED_AMOUNT discount:**
```
promotional_price = MAX(0, ROUND(original_price - discount_value, 2))
```

**Edge cases:**
- If `promotional_price < 0`, set to `0`
- If `max_discount_amount` is null, ignore it (no cap)
- If `original_price` is null, skip this SKU

---

## 7. Acceptance Criteria

- [ ] Markdown worker acquires distributed lock before processing to prevent concurrent recalculation
- [ ] Lock order: Product → SKU (acquired in that order)
- [ ] `MARKDOWN_CREATED` correctly calculates and applies promotional prices
- [ ] `MARKDOWN_ENDED` correctly resets prices to NULL
- [ ] `MARKDOWN_ENDED` is idempotent: no error if rule already removed
- [ ] Redis `product:markdown:active:{skuId}` is set with correct TTL
- [ ] Start-time gate correctly delays processing until `startTime`
- [ ] Lock is always released in `finally` block
- [ ] Failed lock acquisition is retried with backoff
- [ ] SKU_UPDATED events include `changeSource = MARKDOWN_CREATED/UPDATED/ENDED`
