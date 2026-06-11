# Module 13: Markdown Expiry Cronjob

> **Status:** Module 13 of 15 - **IMPLEMENTED**
> **Implementation Date:** June 11, 2026
> **Prerequisites:** Module 06 (business services)
> **Dependents:** None (leaf module)

---

## 1. Overview

This module implements the Markdown Expiry Cronjob — a scheduled job that cleans up expired markdown rules, resets promotional prices to NULL, and publishes `SKU_UPDATED` events. It serves as a self-healing mechanism for cases where `MARKDOWN_ENDED` events from Promotion Service are lost or delayed.

---

## 2. Scheduled Job

### 2.1 File: `worker/scheduler/MarkdownExpiryJob.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/scheduler/MarkdownExpiryJob.java`

- [x] Create `@Component` class
- [x] Inject: `MarkdownExpiryLockRunner`, `MarkdownExpiryProcessor`, `MarkdownExpiryMetrics`
- [x] Configure `@Scheduled(cron = "${app.markdown-expiry.cron:0 */5 * * * *}")` — every 5 minutes by default
- [x] **Run method**: acquires global lock, calls processor, records metrics, handles lock-skip case

---

## 3. Expiry Processor

### 3.1 File: `worker/processor/MarkdownExpiryProcessor.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/processor/MarkdownExpiryProcessor.java`

- [x] Create `@Component` class
- [x] Inject: `MarkdownRepository`, `DistributedLockRepository`, `MarkdownExpiryTransactionalService`, product lock TTL config
- [x] Method `int processExpiredMarkdowns()`:
  - Step 1: Query `product_markdown_applied WHERE expires_at < NOW()` via `MarkdownRepository.findExpiredMarkdowns(Instant.now())`
  - Step 2: Group by productId, sort alphabetically (consistent lock ordering)
  - Step 3: For each product:
    - Acquire distributed lock: `product:lock:markdown-expiry:{productId}` (TTL: configurable, default 30s)
    - Delegate to `MarkdownExpiryTransactionalService` for transactional DB work
    - Release lock
  - Step 4: Log: number of markdowns expired, number of products affected
  - Return total count
- [x] Error handling: log and continue on individual product failure (don't fail entire batch)

### 3.2 File: `worker/processor/MarkdownExpiryTransactionalService.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/processor/MarkdownExpiryTransactionalService.java`

- [x] Create `@Service` class (separate bean to avoid Spring AOP proxy self-invocation)
- [x] Inject: `ProductRepository`, `SkuRepository`, `MarkdownRepository`, `OutboxRepository`, `MarkdownCacheRepository`, `ObjectMapper`
- [x] Method `boolean processProductInTransaction(String productId, List<MarkdownApplied> markdowns)`:
  - Lock product `FOR NO KEY UPDATE` via `findByIdForNoKeyUpdate`
  - Lock all affected SKUs `FOR UPDATE` via `findByIdForUpdate`
  - Reset `promotionalPrice = null` on each SKU
  - Save updated SKUs
  - Delete `product_markdown_applied` records
  - Insert `SKU_UPDATED` outbox events with `changeSource = "MARKDOWN_ENDED"`
  - Delete Redis `product:markdown:active:{skuId}` entries via `MarkdownCacheRepository.deleteBySkuIds`
  - Handle orphaned markdown records (product not found)

### 3.3 File: `worker/scheduler/MarkdownExpiryMetrics.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/scheduler/MarkdownExpiryMetrics.java`

- [x] Create `@Component` class with Micrometer metrics
- [x] Counter: `markdown.expiry.processed` (total expired markdowns processed)
- [x] Counter: `markdown.expiry.errors`
- [x] Timer: `markdown.expiry.duration`
- [x] Gauge: `markdown.expiry.pending_count` (from DB query via `setPendingCount`)

---

## 4. Redis Lock for Job Safety

### 4.1 File: `worker/scheduler/MarkdownExpiryLockRunner.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/scheduler/MarkdownExpiryLockRunner.java`

- [x] Create `@Component` class wrapping `DistributedLockRepository`
- [x] Lock key: `markdown-expiry` (full key becomes `product:lock:markdown-expiry` via `RedisKeyBuilder`)
- [x] TTL: configurable via `app.markdown-expiry.lock-ttl-minutes` (default: 5 minutes)
- [x] If lock not acquired: returns null, caller logs info message

---

## 5. Configuration Properties

The module uses the following configuration properties under the `app` prefix:

| Property | Default | Description |
|---|---|---|
| `app.markdown-expiry.cron` | `0 */5 * * * *` | Cron expression for job schedule |
| `app.markdown-expiry.lock-ttl-minutes` | `5` | Global job lock TTL |
| `app.markdown-expiry.product-lock-ttl-seconds` | `30` | Per-product distributed lock TTL |
| `app.markdown-expiry.batch-size` | `100` | Chunk size for processing (informational) |

---

## 6. Acceptance Criteria

- [x] Job runs on configured cron schedule (every 5 minutes by default)
- [x] Job processes expired markdowns by grouping by product
- [x] Product-level distributed lock prevents concurrent processing of the same product
- [x] Lock ordering (alphabetical product ID) prevents deadlock between concurrent runs
- [x] Expired markdown correctly resets `promotionalPrice = NULL` on SKUs
- [x] `SKU_UPDATED` events with `changeSource = "MARKDOWN_ENDED"` are published via outbox
- [x] Redis markdown cache entries are deleted
- [x] Distributed lock prevents job from running on multiple pods simultaneously
- [x] Job handles errors gracefully and continues processing remaining products
- [x] Metrics correctly track processed count and errors
