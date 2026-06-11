# Module 03: Repository Layer

> **Status:** Module 3 of 15 - **COMPLETED**
> **Implementation Date:** June 11, 2026
> **Last Updated:** June 11, 2026 (folder structure refactored to separate JPA layer from outbound adapters)
> **Prerequisites:** Module 01 (foundation), Module 02 (domain model)
> **Dependents:** Module 06 (business services), Module 09 (outbox), Module 10 (event handler), Module 11 (markdown worker), Module 12 (inventory sync), Module 13 (markdown expiry)

> ## Implementation Summary
> All items in Module 3 have been fully implemented in `java/services/product-service/`.

---

## 1. Overview

This module implements the data access layer: JPA entity models, Spring Data JPA repositories, custom query implementations, and entity-to-domain mappers. All repositories are backed by PostgreSQL as the write store.

### 1.1 Folder Structure

The persistence layer is organized into three clearly separated sub-layers under `infrastructure/persistence/`:

```
infrastructure/persistence/
├── jpa/                           ← Layer 1: JPA / Database
│   ├── entity/                    ← JPA entity classes (9 entities)
│   ├── spring/                    ← Spring Data JPA repository interfaces
│   └── custom/                   ← Custom query interfaces + implementations
│                                  (must be in same package for Spring Data merge)
│
└── outbound/                      ← Layer 3: Outbound Adapter
    ├── mapper/                    ← Domain ↔ JPA entity bidirectional mappers
    └── repository/                ← Implements port/outbound interfaces
```

**Layer separation:**
- `jpa/` — pure infrastructure: no domain imports, handles DB interaction
- `outbound/` — implements `port/outbound` interfaces, bridges domain ↔ infrastructure

---

## 2. JPA Entity Models

### 2.1 File: `infrastructure/persistence/entity/ProductJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/ProductJpaEntity.java`

- [x] Annotate class with `@Entity`, `@Table(name = "products")`
- [x] Map fields: `productId` → `product_id` (PK), `name`, `slug`, `categoryId` → `category_id`, `description` (TEXT), `status`, `deleted` → `is_deleted`, `adminHidden` → `admin_hidden`, `deletedAt` → `deleted_at`, `attributesJson` → `attributes_json` (JSONB), `createdAt` → `created_at`, `updatedAt` → `updated_at`
- [x] Add `@Version` for optimistic locking (`version` column)
- [x] Add `@NoArgsConstructor`, `@AllArgsConstructor`, `@Builder` (Lombok)
- [x] Add `@PrePersist` method: set `createdAt`, `updatedAt`
- [x] Add `@PreUpdate` method: set `updatedAt`

### 2.2 File: `infrastructure/persistence/entity/SkuJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/SkuJpaEntity.java`

- [x] Annotate with `@Entity`, `@Table(name = "product_skus")`
- [x] Map fields: `skuId` → `sku_id` (PK), `productId` → `product_id`, `skuCode` → `sku_code` (UNIQUE), `originalPrice` → `original_price` (DECIMAL(15,2)), `promotionalPrice` → `promotional_price`, `status`, `deleted` → `is_deleted`, `deletedAt` → `deleted_at`, `displayQuantity` → `display_quantity` (INT), `lastInventorySyncAt` → `last_inventory_sync_at` (BIGINT), `variantAttributesJson` → `variant_attributes_json` (JSONB), `createdAt` → `created_at`, `updatedAt` → `updated_at`
- [x] Add `@Version` for optimistic locking
- [x] Add `@ManyToOne(fetch = FetchType.LAZY)` on `productId` referencing `ProductJpaEntity`
- [x] Add `@PrePersist`, `@PreUpdate` lifecycle callbacks

### 2.3 File: `infrastructure/persistence/entity/CategoryJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/CategoryJpaEntity.java`

- [x] Annotate with `@Entity`, `@Table(name = "categories")`
- [x] Map fields: `categoryId` → `category_id` (PK), `parentId` → `parent_id`, `name`, `slug`, `level`, `isLeaf` → `is_leaf`, `createdAt` → `created_at`, `updatedAt` → `updated_at`
- [x] Add self-referential `@ManyToOne(fetch = FetchType.LAZY)` for `parentId`
- [x] Add `@OneToMany(mappedBy = "parent", fetch = FetchType.LAZY)` for children

### 2.4 File: `infrastructure/persistence/entity/AttributeJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/AttributeJpaEntity.java`

- [x] Annotate with `@Entity`, `@Table(name = "attributes")`
- [x] Map fields: `attributeId` → `attribute_id` (PK), `name`, `type`, `createdAt` → `created_at`

### 2.5 File: `infrastructure/persistence/entity/AttributeValueJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/AttributeValueJpaEntity.java`

- [x] Annotate with `@Entity`, `@Table(name = "attribute_values")`
- [x] Map fields: `valueId` → `value_id` (PK), `attributeId` → `attribute_id`, `value`

### 2.6 File: `infrastructure/persistence/entity/CategoryAttributeJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/CategoryAttributeJpaEntity.java`

- [x] Annotate with `@Entity`, `@Table(name = "category_attributes")`
- [x] Map fields: `id`, `categoryId` → `category_id`, `attributeId` → `attribute_id`, `isRequired` → `is_required`, `isVariant` → `is_variant`
- [x] Add `@ManyToOne` for `CategoryJpaEntity` and `AttributeJpaEntity`

### 2.7 File: `infrastructure/persistence/entity/ProductMediaJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/ProductMediaJpaEntity.java`

- [x] Annotate with `@Entity`, `@Table(name = "product_media")`
- [x] Map fields: `mediaId` → `media_id` (PK), `productId` → `product_id`, `skuId` → `sku_id` (nullable), `mediaType` → `media_type`, `url`, `thumbnailUrl` → `thumbnail_url`, `sortOrder` → `sort_order`, `createdAt` → `created_at`, `updatedAt` → `updated_at`
- [x] Add `@ManyToOne` for `ProductJpaEntity`

### 2.8 File: `infrastructure/persistence/entity/MarkdownAppliedJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/MarkdownAppliedJpaEntity.java`

- [x] Annotate with `@Entity`, `@Table(name = "product_markdown_applied")`
- [x] Map fields: `id`, `productId` → `product_id`, `ruleId` → `rule_id`, `skuId` → `sku_id`, `discountType` → `discount_type`, `discountValue` → `discount_value`, `maxDiscountAmount` → `max_discount_amount`, `calculatedPrice` → `calculated_price`, `appliedAt` → `applied_at`, `expiresAt` → `expires_at`
- [x] Add UNIQUE constraint on `(rule_id, sku_id)` via `@Table(uniqueConstraints = ...)`

### 2.9 File: `infrastructure/persistence/entity/OutboxEventJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/OutboxEventJpaEntity.java`

- [x] Annotate with `@Entity`, `@Table(name = "product_outbox_events")`
- [x] Map fields: `eventId` → `event_id` (UUID, PK), `eventType` → `event_type`, `aggregateType` → `aggregate_type`, `aggregateId` → `aggregate_id`, `partitionKey` → `partition_key`, `payload` → `payload` (JSONB), `createdAt` → `created_at`
- [x] Use `@GeneratedValue` strategy for UUID

---

## 3. Spring Data JPA Repositories (`jpa/spring/`)

### 3.1 File: `infrastructure/persistence/jpa/spring/ProductJpaRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/jpa/spring/ProductJpaRepository.java`

- [x] Interface extending `JpaRepository<ProductJpaEntity, String>`, `JpaSpecificationExecutor<ProductJpaEntity>`
- [x] Method `Optional<ProductJpaEntity> findBySlug(String slug)`
- [x] Method `boolean existsBySlug(String slug)`
- [x] Method `Page<ProductJpaEntity> findByCategoryId(String categoryId, Pageable pageable)`
- [x] Method `Page<ProductJpaEntity> findByStatus(String status, Pageable pageable)`
- [x] Method `List<ProductJpaEntity> findByCategoryIdAndStatus(String categoryId, String status)`
- [x] Method `List<ProductJpaEntity> findByIdIn(List<String> ids)`
- [x] Method `@Lock(LockModeType.PESSIMISTIC_WRITE)` `Optional<ProductJpaEntity> findWithLockById(String id)`: `SELECT ... FOR UPDATE`
- [x] Method `@Lock(LockModeType.PESSIMISTIC_WRITE)` `Optional<ProductJpaEntity> findByIdForNoKeyUpdate(String id)`: `SELECT ... FOR NO KEY UPDATE` via `@Query`

### 3.2 File: `infrastructure/persistence/jpa/spring/SkuJpaRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/jpa/spring/SkuJpaRepository.java`

- [x] Interface extending `JpaRepository<SkuJpaEntity, String>`
- [x] Method `Optional<SkuJpaEntity> findBySkuCode(String skuCode)`
- [x] Method `boolean existsBySkuCode(String skuCode)`
- [x] Method `List<SkuJpaEntity> findByProductId(String productId)`
- [x] Method `List<SkuJpaEntity> findByProductIdAndDeletedFalse(String productId)`
- [x] Method `List<SkuJpaEntity> findByIdIn(List<String> ids)`
- [x] Method `@Lock(LockModeType.PESSIMISTIC_WRITE)` `Optional<SkuJpaEntity> findWithLockById(String id)`: `SELECT ... FOR UPDATE`
- [x] Method `Optional<SkuJpaEntity> findByIdAndDeletedFalse(String id)`
- [x] Method `@Query("SELECT s FROM SkuJpaEntity s WHERE s.productId IN :productIds AND s.deleted = false")` `List<SkuJpaEntity> findActiveByProductIds(@Param("productIds") List<String> productIds)`
- [x] Method `@Query` aggregate: `SELECT SUM(s.displayQuantity) FROM SkuJpaEntity s WHERE s.productId = :productId AND s.deleted = false`

### 3.3 File: `infrastructure/persistence/jpa/spring/CategoryJpaRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/jpa/spring/CategoryJpaRepository.java`

- [x] Interface extending `JpaRepository<CategoryJpaEntity, String>`
- [x] Method `Optional<CategoryJpaEntity> findBySlug(String slug)`
- [x] Method `boolean existsBySlug(String slug)`
- [x] Method `List<CategoryJpaEntity> findByParentIdIsNull()`: root categories
- [x] Method `List<CategoryJpaEntity> findByParentId(String parentId)`
- [x] Method `boolean existsByParentId(String parentId)`: check if category has children

### 3.4 File: `infrastructure/persistence/jpa/spring/MarkdownAppliedJpaRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/jpa/spring/MarkdownAppliedJpaRepository.java`

- [x] Interface extending `JpaRepository<MarkdownAppliedJpaEntity, String>`
- [x] Method `List<MarkdownAppliedJpaEntity> findByRuleId(String ruleId)`
- [x] Method `Optional<MarkdownAppliedJpaEntity> findByRuleIdAndSkuId(String ruleId, String skuId)`
- [x] Method `List<MarkdownAppliedJpaEntity> findBySkuId(String skuId)`
- [x] Method `List<MarkdownAppliedJpaEntity> findByProductId(String productId)`
- [x] Method `@Query` `List<MarkdownAppliedJpaEntity> findExpiredMarkdowns(@Param("now") Instant now)`: `WHERE expires_at < :now`
- [x] Method `void deleteByRuleId(String ruleId)`

### 3.5 File: `infrastructure/persistence/jpa/spring/OutboxEventJpaRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/jpa/spring/OutboxEventJpaRepository.java`

- [x] Interface extending `JpaRepository<OutboxEventJpaEntity, UUID>`
- [x] Method `void deleteByCreatedAtBefore(LocalDateTime threshold)`
- [x] Method `@Modifying @Query` `int deleteOlderThan(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit)`: `DELETE WHERE created_at < :threshold LIMIT :limit` (chunked deletion)
- [x] Method `@Query` `List<OutboxEventJpaEntity> findByAggregateIdOrderByCreatedAt(String aggregateId)`

### 3.6 File: `infrastructure/persistence/jpa/spring/ProductMediaJpaRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/jpa/spring/ProductMediaJpaRepository.java`

- [x] Interface extending `JpaRepository<ProductMediaJpaEntity, String>`
- [x] Method `List<ProductMediaJpaEntity> findByProductIdOrderBySortOrder(String productId)`
- [x] Method `List<ProductMediaJpaEntity> findBySkuId(String skuId)`
- [x] Method `void deleteByProductId(String productId)`

---

## 4. Custom Repository Implementations (`jpa/custom/`)

> **Note:** The `*Custom` interface and `*Impl` class **must** be in the same package for Spring Data JPA to automatically merge them. They are placed in `jpa/custom/` to clearly distinguish them from the simple Spring Data repositories in `jpa/spring/`.

### 4.1 Product Custom Repository

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/jpa/custom/ProductJpaRepositoryCustom.java` (interface)

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/jpa/custom/ProductJpaRepositoryImpl.java` (implementation)

- [x] Create `ProductJpaRepositoryCustom` interface with method `Page<ProductJpaEntity> findByFilters(String categoryId, String status, String search, Boolean deleted, Pageable pageable)`
- [x] Implement `ProductJpaRepositoryImpl` with `EntityManager`
- [x] Use `CriteriaBuilder` for dynamic WHERE clause
- [x] Build count query separately for pagination
- [x] Implement `List<ProductJpaEntity> findByCategoryIdAndStatusIn(String categoryId, List<String> statuses)` for FSM batch updates

### 4.2 Sku Custom Repository

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/jpa/custom/SkuJpaRepositoryCustom.java` (interface)

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/jpa/custom/SkuJpaRepositoryImpl.java` (implementation)

- [x] Create `SkuJpaRepositoryCustom` interface with method `int sumDisplayQuantityByProductId(String productId)`
- [x] Implement using `CriteriaBuilder` with `Sum` aggregation
- [x] Implement `Map<String, Integer> sumDisplayQuantityByProductIds(List<String> productIds)`: batch aggregation for FSM auto-hide checks

### 4.3 Category Custom Repository

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/jpa/custom/CategoryJpaRepositoryCustom.java` (interface)

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/jpa/custom/CategoryJpaRepositoryImpl.java` (implementation)

- [x] Create `CategoryJpaRepositoryCustom` interface
- [x] Implement `List<CategoryJpaEntity> findAllDescendants(String categoryId)`: recursive CTE query for category tree
- [x] Implement `List<String> findAllDescendantIds(String categoryId)`: recursive CTE returning IDs only
- [x] Implement `List<CategoryJpaEntity> findRootToLeafPath(String categoryId)`: path from root to leaf

---

## 5. Entity-to-Domain Mappers (`outbound/mapper/`)

### 5.1 File: `infrastructure/persistence/outbound/mapper/ProductJpaMapper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/outbound/mapper/ProductJpaMapper.java`

- [x] Create `@Component` class `ProductJpaMapper`
- [x] Method `Product toDomain(ProductJpaEntity entity)`: map all fields, deserialize `attributesJson` to `Map<String, Object>`
- [x] Method `ProductJpaEntity toEntity(Product product)`: map all fields, serialize `attributes` to `attributesJson`
- [x] Handle null inputs gracefully
- [x] Use `ObjectMapper` for JSONB serialization/deserialization

### 5.2 File: `infrastructure/persistence/outbound/mapper/SkuJpaMapper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/outbound/mapper/SkuJpaMapper.java`

- [x] Create `@Component` class
- [x] Method `Sku toDomain(SkuJpaEntity entity)`: map all fields, deserialize `variantAttributesJson`
- [x] Method `SkuJpaEntity toEntity(Sku sku)`: map all fields, serialize `variantAttributes`
- [x] Handle `promotionalPrice` as nullable `BigDecimal`

### 5.3 File: `infrastructure/persistence/outbound/mapper/CategoryJpaMapper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/outbound/mapper/CategoryJpaMapper.java`

- [x] Create `@Component` class
- [x] Method `Category toDomain(CategoryJpaEntity entity)`
- [x] Method `CategoryJpaEntity toEntity(Category category)`

### 5.4 File: `infrastructure/persistence/outbound/mapper/ProductMediaJpaMapper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/outbound/mapper/ProductMediaJpaMapper.java`

- [x] Create `@Component` class
- [x] Method `ProductMedia toDomain(ProductMediaJpaEntity entity)`
- [x] Method `ProductMediaJpaEntity toEntity(ProductMedia domain)`

### 5.5 File: `infrastructure/persistence/outbound/mapper/MarkdownAppliedJpaMapper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/outbound/mapper/MarkdownAppliedJpaMapper.java`

- [x] Create `@Component` class
- [x] Method `MarkdownApplied toDomain(MarkdownAppliedJpaEntity entity)`
- [x] Method `MarkdownAppliedJpaEntity toEntity(MarkdownApplied domain)`

### 5.6 File: `infrastructure/persistence/outbound/mapper/OutboxEventJpaMapper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/outbound/mapper/OutboxEventJpaMapper.java`

- [x] Create `@Component` class
- [x] Method `OutboxEvent toDomain(OutboxEventJpaEntity entity)`
- [x] Method `OutboxEventJpaEntity toEntity(OutboxEvent domain, String payloadJson)`

---

## 6. Port (Interface) Definitions (`port/outbound/`)

### 6.1 File: `port/outbound/database/ProductRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/database/ProductRepository.java`

- [x] Define interface mirroring domain operations: `save(Product)`, `findById(ProductId)`, `findBySlug(Slug)`, `existsBySlug(Slug)`, `findByCategoryId(CategoryId, Pageable)`, `findByIdForUpdate(ProductId)`, `findByIdForNoKeyUpdate(ProductId)`, `findByFilters(...)`, `delete(Product)`
- [x] All methods return domain objects (converted from JPA entities in implementation)

### 6.2 File: `port/outbound/database/SkuRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/database/SkuRepository.java`

- [x] Define interface: `save(Sku)`, `findById(SkuId)`, `findBySkuCode(SkuCode)`, `findByProductId(ProductId)`, `findByIdForUpdate(SkuId)`, `findByIdAndNotDeleted(SkuId)`, `findByIds(List<SkuId>)`, `sumDisplayQuantityByProductId(ProductId)`, `saveAll(List<Sku>)`, `delete(Sku)`

### 6.3 File: `port/outbound/database/CategoryRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/database/CategoryRepository.java`

- [x] Define interface: `save(Category)`, `findById(CategoryId)`, `findBySlug(Slug)`, `existsBySlug(Slug)`, `findChildren(CategoryId)`, `findRootCategories()`, `findAllDescendants(CategoryId)`, `hasChildren(CategoryId)`, `delete(Category)`

### 6.4 File: `port/outbound/database/MarkdownRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/database/MarkdownRepository.java`

- [x] Define interface: `save(MarkdownApplied)`, `findByRuleId(String)`, `findBySkuId(String)`, `findByProductId(ProductId)`, `findExpiredMarkdowns(Instant)`, `deleteByRuleId(String)`, `delete(MarkdownApplied)`

### 6.5 File: `port/outbound/database/OutboxRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/database/OutboxRepository.java`

- [x] Define interface: `save(OutboxEvent)`, `deleteOlderThan(LocalDateTime, int limit)`
- [x] `saveAll(List<OutboxEvent>)` for batch outbox inserts

### 6.6 File: `port/outbound/database/ProductMediaRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/database/ProductMediaRepository.java`

- [x] Define interface: `save(ProductMedia)`, `findByProductId(String)`, `findBySkuId(String)`, `deleteByProductId(String)`, `saveAll(List<ProductMedia>)`

---

## 7. Repository Implementations (`outbound/repository/`)

### 7.1 File: `infrastructure/persistence/outbound/repository/ProductRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/outbound/repository/ProductRepositoryImpl.java`

- [x] Implement `ProductRepository` with `@Repository`
- [x] Inject `ProductJpaRepository`, `ProductJpaRepositoryCustom`, `ProductJpaMapper`
- [x] Implement all port methods, converting between domain and JPA entities
- [x] Use `@Transactional(readOnly = true)` for read methods
- [x] Use `@Transactional` for write methods
- [x] Use `ProductJpaRepositoryCustom` for `findByFilters` and `findByCategoryIdAndStatusIn`

### 7.2 File: `infrastructure/persistence/outbound/repository/SkuRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/outbound/repository/SkuRepositoryImpl.java`

- [x] Implement `SkuRepository`
- [x] Inject `SkuJpaRepository`, `SkuJpaMapper`
- [x] Implement all methods

### 7.3 File: `infrastructure/persistence/outbound/repository/CategoryRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/outbound/repository/CategoryRepositoryImpl.java`

- [x] Implement `CategoryRepository`
- [x] Inject `CategoryJpaRepository`, `CategoryJpaMapper`
- [x] Implement all methods

### 7.4 File: `infrastructure/persistence/outbound/repository/ProductMediaRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/outbound/repository/ProductMediaRepositoryImpl.java`

- [x] Implement `ProductMediaRepository`
- [x] Inject `ProductMediaJpaRepository`, `ProductMediaJpaMapper`

### 7.5 File: `infrastructure/persistence/outbound/repository/MarkdownRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/outbound/repository/MarkdownRepositoryImpl.java`

- [x] Implement `MarkdownRepository`
- [x] Inject `MarkdownAppliedJpaRepository`, `MarkdownAppliedJpaMapper`
- [x] Implement all methods

### 7.6 File: `infrastructure/persistence/outbound/repository/OutboxRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/outbound/repository/OutboxRepositoryImpl.java`

- [x] Implement `OutboxRepository`
- [x] Inject `OutboxEventJpaRepository`, `OutboxEventJpaMapper`
- [x] Implement `deleteOlderThan` with chunked deletion (LIMIT 10000)

---

## 8. Acceptance Criteria

- [x] All JPA entities map correctly to PostgreSQL schema (verify with `ddl-auto: validate`)
- [x] `findWithLockById` uses `SELECT ... FOR UPDATE`
- [x] `findByIdForNoKeyUpdate` uses `SELECT ... FOR NO KEY UPDATE`
- [x] Recursive CTE for `findAllDescendants` works correctly for multi-level category trees
- [x] Entity-to-domain mappers handle null values and JSONB serialization/deserialization
- [x] All repository implementations are registered as Spring beans
- [ ] Unit tests cover entity-to-domain conversion for all mappers
