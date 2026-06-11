# Module 06: Business Services

> **Status:** Module 6 of 15
> **Prerequisites:** Module 02 (domain model), Module 03 (repository), Module 04 (Redis), Module 05 (state machine)
> **Dependents:** Module 07 (REST API), Module 08 (gRPC), Module 09 (outbox), Module 11 (markdown worker), Module 12 (inventory sync), Module 13 (markdown expiry)

---

## 1. Overview

This module implements the core business services: ProductService, SkuService, CategoryService, MarkdownService, and InventorySyncService. Services contain application-level orchestration logic, transaction boundaries, and coordinate between domain, repositories, and cache.

---

## 2. Inbound Port Interfaces (Application Layer)

### 2.1 File: `port/inbound/ProductServicePort.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/inbound/ProductServicePort.java`

- [ ] Define interface with methods: `create(Product)`, `findById(String productId)`, `search(ProductSearchCriteria)`, `update(String productId, Product)`, `delete(String productId)`, `submit(String productId)`, `activate(String productId)`, `hide(String productId)`, `show(String productId)`
- [ ] Define `ProductSearchCriteria` record: `categoryId`, `status`, `search`, `page`, `size`, `sort`

### 2.2 File: `port/inbound/SkuServicePort.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/inbound/SkuServicePort.java`

- [ ] Define interface: `createSku(String productId, Sku)`, `updateSku(String productId, String skuId, Sku)`, `deleteSku(String productId, String skuId)`, `findById(String skuId)`, `findByProductId(String productId)`

### 2.3 File: `port/inbound/CategoryServicePort.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/inbound/CategoryServicePort.java`

- [ ] Define interface: `create(Category)`, `findById(String categoryId)`, `findTree()`, `findAll()`, `update(String categoryId, Category)`, `delete(String categoryId)`

### 2.4 File: `port/inbound/MarkdownServicePort.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/inbound/MarkdownServicePort.java`

- [ ] Define interface: `applyMarkdown(MarkdownApplication)`, `removeMarkdown(String ruleId)`, `recalculateMarkdown(String ruleId)`, `getActiveMarkdown(String skuId)`
- [ ] Define `MarkdownApplication` record: `ruleId`, `productId`, `skuId`, `discountType`, `discountValue`, `maxDiscountAmount`, `startTime`, `expiresAt`

---

## 3. Product Service Implementation

### 3.1 File: `service/impl/ProductServiceImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/service/impl/ProductServiceImpl.java`

**Main service implementing `ProductServicePort`.**

- [ ] Annotate with `@Service`, `@Transactional`
- [ ] Inject: `ProductRepository`, `SkuRepository`, `CategoryRepository`, `OutboxRepository`, `SkuCacheRepository`, `ProductFsmFacade`, `ProductMapper`
- [ ] **Create flow** (`create`):
  - Validate `categoryId` exists
  - Check duplicate slug
  - Call `product.initialize()`
  - Save product with SKU
  - Batch insert outbox events (`PRODUCT_CREATED`)
  - Warm cache for all SKUs
  - Return saved product
- [ ] **Get flow** (`findById`): read-through cache pattern — check Redis first, fallback to DB
- [ ] **Update flow** (`update`):
  - Lock product: `findByIdForNoKeyUpdate`
  - Update fields
  - Batch insert outbox events (`PRODUCT_UPDATED`)
  - In-band cache refresh (overwrite `product:sku:{skuId}` for each SKU)
- [ ] **Delete flow** (`delete`):
  - Lock product
  - Validate `product.canBeDeleted()` (DRAFT or HIDDEN only)
  - Soft delete: set `isDeleted = true`, `deletedAt = now`
  - Batch insert outbox events (`PRODUCT_DELETED`)
  - Delete cache entries for all SKUs
- [ ] **Status transitions** (`submit`, `activate`, `hide`, `show`): delegate to `ProductFsmFacade`, then save and publish `PRODUCT_STATUS_CHANGED` event
- [ ] **Search flow** (`search`): use `ProductJpaRepository.findByFilters` with pagination

### 3.2 File: `service/impl/SkuServiceImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/service/impl/SkuServiceImpl.java`

- [ ] Annotate with `@Service`, `@Transactional`
- [ ] Inject: `SkuRepository`, `ProductRepository`, `OutboxRepository`, `SkuCacheRepository`, `MarkdownCalculator`, `MarkdownCacheRepository`
- [ ] **Create SKU flow** (`createSku`):
  - Lock product: `findByIdForNoKeyUpdate`
  - Validate product is not deleted
  - Check duplicate `skuCode`
  - Call `sku.initialize(productId)`
  - Save SKU
  - Batch insert outbox events (`SKU_CREATED`)
  - Warm cache
- [ ] **Update SKU flow** (`updateSku`):
  - Lock product: `findByIdForNoKeyUpdate`
  - Lock SKU: `findWithLockById`
  - If `originalPrice` changed AND SKU has active markdown: recalculate `promotionalPrice` via `MarkdownCalculator`
  - Save SKU
  - Insert `SKU_UPDATED` outbox with `changedFields[]` populated
  - In-band cache overwrite
- [ ] **Delete SKU flow** (`deleteSku`):
  - Lock product + SKU
  - Soft delete: set `isDeleted = true`, `deletedAt = now`
  - Insert `SKU_DELETED` outbox
  - Delete cache entry
- [ ] **Recalculate markdown** helper: if price changed, fetch active markdown from `MarkdownCacheRepository`, recalculate, update DB

### 3.3 File: `service/impl/CategoryServiceImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/service/impl/CategoryServiceImpl.java`

- [ ] Annotate with `@Service`, `@Transactional`
- [ ] Inject: `CategoryRepository`
- [ ] **Create flow**: validate parent exists if `parentId` not null, check slug uniqueness, `category.initialize()`, save
- [ ] **Get flow**: find by ID with children eagerly loaded
- [ ] **Tree flow**: build category tree starting from root categories, recursively load children
- [ ] **Update flow**: validate slug uniqueness (excluding self), update fields, save
- [ ] **Delete flow**: check `hasChildren()` returns false, soft delete, insert `CATEGORY_DELETED` outbox

### 3.4 File: `service/impl/MarkdownServiceImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/service/impl/MarkdownServiceImpl.java`

- [ ] Annotate with `@Service`, `@Transactional`
- [ ] Inject: `ProductRepository`, `SkuRepository`, `MarkdownRepository`, `OutboxRepository`, `SkuCacheRepository`, `MarkdownCacheRepository`, `MarkdownCalculator`, `DistributedLockRepository`
- [ ] **Apply markdown flow** (`applyMarkdown`):
  - Acquire distributed lock: `product:lock:markdown:{productId}` (TTL: 30s)
  - Group SKUs by `productId`
  - For each product: lock Product `FOR NO KEY UPDATE`, lock SKUs `FOR UPDATE`
  - Calculate `promotionalPrice` per SKU using `MarkdownCalculator`
  - Batch update `promotionalPrice` on SKUs
  - Insert/delete `product_markdown_applied` records
  - Insert `SKU_UPDATED` outbox events with `changeSource = MARKDOWN_CREATED`
  - Set `product:markdown:active:{skuId}` cache with TTL
  - Release lock
- [ ] **Remove markdown flow** (`removeMarkdown`): query by `ruleId`, reset `promotionalPrice = null`, delete mapping, insert `SKU_UPDATED` outbox, delete markdown cache
- [ ] **Recalculate flow** (`recalculateMarkdown`): same as apply but with existing rule metadata
- [ ] **Start-time gate**: if `startTime > now`, persist to Redis ZSET for delayed processing

---

## 4. Inventory Sync Service (Phase 2 orchestration)

### 4.1 File: `service/InventorySyncOrchestrator.java`

**Path:** `product-service/src/main/java/com/platform/productservice/service/InventorySyncOrchestrator.java`

- [ ] Create `@Service` class (used by Phase 2 worker)
- [ ] Inject: `SkuRepository`, `ProductRepository`, `OutboxRepository`, `SkuCacheRepository`, `ProductFsmFacade`, `FsmTriggerCalculator`
- [ ] Method `syncBatch(Map<String, List<PendingSyncItem>> itemsByProductId)`:
  - For each product ID: start transaction
  - Lock product: `findByIdForNoKeyUpdate`
  - Get all SKUs for product
  - Apply inventory updates: only update if `eventTimestamp > sku.lastInventorySyncAt`
  - Call `FsmTriggerCalculator.calculateFsmTrigger(product, updatedSkus)`
  - Execute FSM trigger if present
  - Batch insert outbox events (`SKU_UPDATED` with `changeSource = INVENTORY_SYNC_PHASE2`)
  - Save all SKUs
  - Commit transaction
  - Acknowledge ZSET pending items
  - Handle `OptimisticLockingFailureException`: requeue items for retry
- [ ] Use `@Transactional(isolation = Isolation.READ_COMMITTED)` for Phase 2 transactions

---

## 5. Event Publisher (Outbox Integration)

### 5.1 File: `port/outbound/DomainEventPublisher.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/DomainEventPublisher.java`

- [ ] Define interface: `void publish(DomainEvent event)`, `void publishAll(List<DomainEvent> events)`
- [ ] Method signature for transactional outbox: `void saveToOutbox(DomainEvent event)` (internal)

### 5.2 File: `service/DomainEventPublisherImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/service/DomainEventPublisherImpl.java`

- [ ] Implement `DomainEventPublisher`
- [ ] Inject `OutboxRepository`, `ObjectMapper`
- [ ] `publish`: convert `DomainEvent` to JSON, create `OutboxEvent`, save to DB within same transaction (called from service methods)
- [ ] `publishAll`: batch save multiple outbox events
- [ ] Note: actual Kafka publishing is handled by Debezium CDC (see Module 09)

---

## 6. DTO Mappers (MapStruct)

### 6.1 File: `dto/mapper/ProductMapper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/mapper/ProductMapper.java`

- [ ] Define MapStruct interface: `Product toDomain(CreateProductRequest)`, `Product toDomain(UpdateProductRequest)`, `ProductResponse toResponse(Product)`
- [ ] MapStruct will generate implementation at compile time

### 6.2 File: `dto/mapper/SkuMapper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/mapper/SkuMapper.java`

- [ ] Define MapStruct interface: `Sku toDomain(CreateSkuRequest)`, `SkuResponse toResponse(Sku)`

### 6.3 File: `dto/mapper/CategoryMapper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/mapper/CategoryMapper.java`

- [ ] Define MapStruct interface: `Category toDomain(CreateCategoryRequest)`, `CategoryResponse toResponse(Category)`, `CategoryTreeResponse toTreeResponse(Category)`

---

## 7. Outbox Service (Transaction Integration)

### 7.1 File: `service/OutboxService.java`

**Path:** `product-service/src/main/java/com/platform/productservice/service/OutboxService.java`

- [ ] Create `@Service` class
- [ ] Inject `OutboxRepository`, `ObjectMapper`
- [ ] Method `void saveEvent(DomainEvent event, String aggregateId, String partitionKey)`: serialize event to JSON, create `OutboxEvent`, save
- [ ] Method `void saveEvents(List<DomainEvent> events, List<String> aggregateIds, List<String> partitionKeys)`: batch insert
- [ ] These methods are called by business services within `@Transactional` boundaries

---

## 8. Acceptance Criteria

- [ ] `ProductService.create` atomically saves product, SKUs, and outbox events in one transaction
- [ ] `ProductService.update` acquires pessimistic lock (`FOR NO KEY UPDATE`) before updating
- [ ] `SkuService.updateSku` acquires locks in order: Product → SKU
- [ ] Markdown recalculation is triggered when `originalPrice` changes on a SKU with active markdown
- [ ] `InventorySyncOrchestrator` groups items by `productId` and processes in transactions
- [ ] `FsmTriggerCalculator` correctly determines FSM triggers during inventory sync
- [ ] `DomainEventPublisher` saves events to outbox within the same transaction as domain changes
- [ ] Read-through cache pattern in `findById` correctly falls back to DB on cache miss
