# Module 13: Markdown Expiry Cronjob

> **Status:** Module 13 of 15
> **Prerequisites:** Module 06 (business services)
> **Dependents:** None (leaf module)

---

## 1. Overview

This module implements the Markdown Expiry Cronjob — a scheduled job that cleans up expired markdown rules, resets promotional prices to NULL, and publishes `SKU_UPDATED` events. It serves as a self-healing mechanism for cases where `MARKDOWN_ENDED` events from Promotion Service are lost or delayed.

---

## 2. Scheduled Job

### 2.1 File: `worker/scheduler/MarkdownExpiryJob.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/scheduler/MarkdownExpiryJob.java`

- [ ] Create `@Component` class
- [ ] Inject: `DistributedLockRunner`, `MarkdownExpiryProcessor`, `MarkdownExpiryMetrics`
- [ ] Configure `@Scheduled(cron = "${app.markdown-expiry.cron:0 */5 * * * *}")` — every 5 minutes by default
- [ ] **Run method**:
  ```java
  @Scheduled(cron = "0 */5 * * * *")
  public void run() {
      lockRunner.runWithLock("product:lock:markdown-expiry", Duration.ofMinutes(5), () -> {
          log.info("Starting markdown expiry scan");
          int processed = processor.processExpiredMarkdowns();
          log.info("Markdown expiry scan completed, processed {} markdowns", processed);
      });
  }
  ```

---

## 3. Expiry Processor

### 3.1 File: `worker/processor/MarkdownExpiryProcessor.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/processor/MarkdownExpiryProcessor.java`

- [ ] Create `@Component` class
- [ ] Inject: `MarkdownRepository`, `SkuRepository`, `OutboxRepository`, `SkuCacheRepository`, `OutboxService`, `DistributedLockRepository`
- [ ] Method `int processExpiredMarkdowns()`:
  - Step 1: Query `product_markdown_applied WHERE expires_at < NOW()` — fetch in chunks of 100
  - Step 2: For each chunk:
    - Extract distinct `productId` values
    - Sort product IDs alphabetically (consistent lock ordering)
    - For each product:
      - Acquire distributed lock: `product:lock:markdown-expiry:{productId}` (TTL: 30s)
      - Query full list of expired markdowns for this product
      - Lock product `FOR NO KEY UPDATE`
      - Lock all affected SKUs `FOR UPDATE`
      - Reset `promotionalPrice = NULL` on each SKU
      - Delete `product_markdown_applied` records
      - Insert `SKU_UPDATED` outbox events with `changeSource = MARKDOWN_ENDED`
      - Delete Redis `product:markdown:active:{skuId}` entries
      - Release lock
    - Log: number of markdowns expired, number of products affected
  - Return total count
- [ ] Error handling: log and continue on individual product failure (don't fail entire batch)

### 3.2 File: `worker/scheduler/MarkdownExpiryMetrics.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/scheduler/MarkdownExpiryMetrics.java`

- [ ] Create `@Component` class with Micrometer metrics
- [ ] Counter: `markdown.expiry.processed` (total expired markdowns processed)
- [ ] Counter: `markdown.expiry.errors`
- [ ] Timer: `markdown.expiry.duration`
- [ ] Gauge: `markdown.expiry.pending_count` (from DB query)

---

## 4. Redis Lock for Job Safety

### 4.1 File: `worker/scheduler/MarkdownExpiryLockRunner.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/scheduler/MarkdownExpiryLockRunner.java`

- [ ] Use existing `DistributedLockRunner` (from Module 04) with lock key `product:lock:markdown-expiry`
- [ ] TTL: 5 minutes (configurable)
- [ ] If lock not acquired: skip run, log info message

---

## 5. Acceptance Criteria

- [ ] Job runs on configured cron schedule (every 5 minutes by default)
- [ ] Job processes expired markdowns in chunks of 100
- [ ] Product-level distributed lock prevents concurrent processing of the same product
- [ ] Lock ordering (alphabetical product ID) prevents deadlock between concurrent runs
- [ ] Expired markdown correctly resets `promotionalPrice = NULL` on SKUs
- [ ] `SKU_UPDATED` events with `changeSource = MARKDOWN_ENDED` are published
- [ ] Redis markdown cache entries are deleted
- [ ] Distributed lock prevents job from running on multiple pods simultaneously
- [ ] Job handles errors gracefully and continues processing remaining products
- [ ] Metrics correctly track processed count and errors
