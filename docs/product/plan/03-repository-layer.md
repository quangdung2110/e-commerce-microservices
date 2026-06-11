# Module 03: Repository Layer

> **Status:** Module 3 of 15
> **Prerequisites:** Module 01 (foundation), Module 02 (domain model)
> **Dependents:** Module 06 (business services), Module 09 (outbox), Module 10 (event handler), Module 11 (markdown worker), Module 12 (inventory sync), Module 13 (markdown expiry)

---

## 1. Overview

This module implements the data access layer: JPA entity models, Spring Data JPA repositories, custom query implementations, and entity-to-domain mappers. All repositories are backed by PostgreSQL as the write store.

---

## 2. JPA Entity Models

### 2.1 File: `infrastructure/persistence/entity/ProductJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/ProductJpaEntity.java`

- [ ] Annotate class with `@Entity`, `@Table(name = "products")`
- [ ] Map fields: `productId` → `product_id` (PK), `name`, `slug`, `categoryId` → `category_id`, `description` (TEXT), `status`, `deleted` → `is_deleted`, `adminHidden` → `admin_hidden`, `deletedAt` → `deleted_at`, `attributesJson` → `attributes_json` (JSONB), `createdAt` → `created_at`, `updatedAt` → `updated_at`
- [ ] Add `@Version` for optimistic locking (`version` column)
- [ ] Add `@NoArgsConstructor`, `@AllArgsConstructor`, `@Builder` (Lombok)
- [ ] Add `@PrePersist` method: set `createdAt`, `updatedAt`
- [ ] Add `@PreUpdate` method: set `updatedAt`

### 2.2 File: `infrastructure/persistence/entity/SkuJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/SkuJpaEntity.java`

- [ ] Annotate with `@Entity`, `@Table(name = "product_skus")`
- [ ] Map fields: `skuId` → `sku_id` (PK), `productId` → `product_id`, `skuCode` → `sku_code` (UNIQUE), `originalPrice` → `original_price` (DECIMAL(15,2)), `promotionalPrice` → `promotional_price`, `status`, `deleted` → `is_deleted`, `deletedAt` → `deleted_at`, `displayQuantity` → `display_quantity` (INT), `lastInventorySyncAt` → `last_inventory_sync_at` (BIGINT), `variantAttributesJson` → `variant_attributes_json` (JSONB), `createdAt` → `created_at`, `updatedAt` → `updated_at`
- [ ] Add `@Version` for optimistic locking
- [ ] Add `@ManyToOne(fetch = FetchType.LAZY)` on `productId` referencing `ProductJpaEntity`
- [ ] Add `@PrePersist`, `@PreUpdate` lifecycle callbacks

### 2.3 File: `infrastructure/persistence/entity/CategoryJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/CategoryJpaEntity.java`

- [ ] Annotate with `@Entity`, `@Table(name = "categories")`
- [ ] Map fields: `categoryId` → `category_id` (PK), `parentId` → `parent_id`, `name`, `slug`, `level`, `isLeaf` → `is_leaf`, `createdAt` → `created_at`, `updatedAt` → `updated_at`
- [ ] Add self-referential `@ManyToOne(fetch = FetchType.LAZY)` for `parentId`
- [ ] Add `@OneToMany(mappedBy = "parent", fetch = FetchType.LAZY)` for children

### 2.4 File: `infrastructure/persistence/entity/AttributeJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/AttributeJpaEntity.java`

- [ ] Annotate with `@Entity`, `@Table(name = "attributes")`
- [ ] Map fields: `attributeId` → `attribute_id` (PK), `name`, `type`, `createdAt` → `created_at`

### 2.5 File: `infrastructure/persistence/entity/AttributeValueJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/AttributeValueJpaEntity.java`

- [ ] Annotate with `@Entity`, `@Table(name = "attribute_values")`
- [ ] Map fields: `valueId` → `value_id` (PK), `attributeId` → `attribute_id`, `value`

### 2.6 File: `infrastructure/persistence/entity/CategoryAttributeJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/CategoryAttributeJpaEntity.java`

- [ ] Annotate with `@Entity`, `@Table(name = "category_attributes")`
- [ ] Map fields: `id`, `categoryId` → `category_id`, `attributeId` → `attribute_id`, `isRequired` → `is_required`, `isVariant` → `is_variant`
- [ ] Add `@ManyToOne` for `CategoryJpaEntity` and `AttributeJpaEntity`

### 2.7 File: `infrastructure/persistence/entity/ProductMediaJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/ProductMediaJpaEntity.java`

- [ ] Annotate with `@Entity`, `@Table(name = "product_media")`
- [ ] Map fields: `mediaId` → `media_id` (PK), `productId` → `product_id`, `skuId` → `sku_id` (nullable), `mediaType` → `media_type`, `url`, `thumbnailUrl` → `thumbnail_url`, `sortOrder` → `sort_order`, `createdAt` → `created_at`, `updatedAt` → `updated_at`
- [ ] Add `@ManyToOne` for `ProductJpaEntity`

### 2.8 File: `infrastructure/persistence/entity/MarkdownAppliedJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/MarkdownAppliedJpaEntity.java`

- [ ] Annotate with `@Entity`, `@Table(name = "product_markdown_applied")`
- [ ] Map fields: `id`, `productId` → `product_id`, `ruleId` → `rule_id`, `skuId` → `sku_id`, `discountType` → `discount_type`, `discountValue` → `discount_value`, `maxDiscountAmount` → `max_discount_amount`, `calculatedPrice` → `calculated_price`, `appliedAt` → `applied_at`, `expiresAt` → `expires_at`
- [ ] Add UNIQUE constraint on `(rule_id, sku_id)` via `@Table(uniqueConstraints = ...)`

### 2.9 File: `infrastructure/persistence/entity/OutboxEventJpaEntity.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/entity/OutboxEventJpaEntity.java`

- [ ] Annotate with `@Entity`, `@Table(name = "product_outbox_events")`
- [ ] Map fields: `eventId` → `event_id` (UUID, PK), `eventType` → `event_type`, `aggregateType` → `aggregate_type`, `aggregateId` → `aggregate_id`, `partitionKey` → `partition_key`, `payload` → `payload` (JSONB), `createdAt` → `created_at`
- [ ] Use `@GeneratedValue` strategy for UUID

---

## 3. Spring Data JPA Repositories

### 3.1 File: `infrastructure/persistence/repository/ProductJpaRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/repository/ProductJpaRepository.java`

- [ ] Interface extending `JpaRepository<ProductJpaEntity, String>`, `JpaSpecificationExecutor<ProductJpaEntity>`
- [ ] Method `Optional<ProductJpaEntity> findBySlug(String slug)`
- [ ] Method `boolean existsBySlug(String slug)`
- [ ] Method `Page<ProductJpaEntity> findByCategoryId(String categoryId, Pageable pageable)`
- [ ] Method `Page<ProductJpaEntity> findByStatus(String status, Pageable pageable)`
- [ ] Method `List<ProductJpaEntity> findByCategoryIdAndStatus(String categoryId, String status)`
- [ ] Method `List<ProductJpaEntity> findByIdIn(List<String> ids)`
- [ ] Method `@Lock(LockModeType.PESSIMISTIC_WRITE)` `Optional<ProductJpaEntity> findWithLockById(String id)`: `SELECT ... FOR UPDATE`
- [ ] Method `@Lock(LockModeType.PESSIMISTIC_WRITE)` `Optional<ProductJpaEntity> findByIdForNoKeyUpdate(String id)`: `SELECT ... FOR NO KEY UPDATE` via `@Query`

### 3.2 File: `infrastructure/persistence/repository/SkuJpaRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/repository/SkuJpaRepository.java`

- [ ] Interface extending `JpaRepository<SkuJpaEntity, String>`
- [ ] Method `Optional<SkuJpaEntity> findBySkuCode(String skuCode)`
- [ ] Method `boolean existsBySkuCode(String skuCode)`
- [ ] Method `List<SkuJpaEntity> findByProductId(String productId)`
- [ ] Method `List<SkuJpaEntity> findByProductIdAndDeletedFalse(String productId)`
- [ ] Method `List<SkuJpaEntity> findByIdIn(List<String> ids)`
- [ ] Method `@Lock(LockModeType.PESSIMISTIC_WRITE)` `Optional<SkuJpaEntity> findWithLockById(String id)`: `SELECT ... FOR UPDATE`
- [ ] Method `Optional<SkuJpaEntity> findByIdAndDeletedFalse(String id)`
- [ ] Method `@Query("SELECT s FROM SkuJpaEntity s WHERE s.productId IN :productIds AND s.deleted = false")` `List<SkuJpaEntity> findActiveByProductIds(@Param("productIds") List<String> productIds)`
- [ ] Method `@Query` aggregate: `SELECT SUM(s.displayQuantity) FROM SkuJpaEntity s WHERE s.productId = :productId AND s.deleted = false`

### 3.3 File: `infrastructure/persistence/repository/CategoryJpaRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/repository/CategoryJpaRepository.java`

- [ ] Interface extending `JpaRepository<CategoryJpaEntity, String>`
- [ ] Method `Optional<CategoryJpaEntity> findBySlug(String slug)`
- [ ] Method `boolean existsBySlug(String slug)`
- [ ] Method `List<CategoryJpaEntity> findByParentIdIsNull()`: root categories
- [ ] Method `List<CategoryJpaEntity> findByParentId(String parentId)`
- [ ] Method `boolean existsByParentId(String parentId)`: check if category has children

### 3.4 File: `infrastructure/persistence/repository/MarkdownAppliedJpaRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/repository/MarkdownAppliedJpaRepository.java`

- [ ] Interface extending `JpaRepository<MarkdownAppliedJpaEntity, String>`
- [ ] Method `List<MarkdownAppliedJpaEntity> findByRuleId(String ruleId)`
- [ ] Method `Optional<MarkdownAppliedJpaEntity> findByRuleIdAndSkuId(String ruleId, String skuId)`
- [ ] Method `List<MarkdownAppliedJpaEntity> findBySkuId(String skuId)`
- [ ] Method `List<MarkdownAppliedJpaEntity> findByProductId(String productId)`
- [ ] Method `@Query` `List<MarkdownAppliedJpaEntity> findExpiredMarkdowns(@Param("now") Instant now)`: `WHERE expires_at < :now`
- [ ] Method `void deleteByRuleId(String ruleId)`

### 3.5 File: `infrastructure/persistence/repository/OutboxEventJpaRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/repository/OutboxEventJpaRepository.java`

- [ ] Interface extending `JpaRepository<OutboxEventJpaEntity, UUID>`
- [ ] Method `void deleteByCreatedAtBefore(LocalDateTime threshold)`
- [ ] Method `@Modifying @Query` `int deleteOlderThan(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit)`: `DELETE WHERE created_at < :threshold LIMIT :limit` (chunked deletion)
- [ ] Method `@Query` `List<OutboxEventJpaEntity> findByAggregateIdOrderByCreatedAt(String aggregateId)`

### 3.6 File: `infrastructure/persistence/repository/ProductMediaJpaRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/repository/ProductMediaJpaRepository.java`

- [ ] Interface extending `JpaRepository<ProductMediaJpaEntity, String>`
- [ ] Method `List<ProductMediaJpaEntity> findByProductIdOrderBySortOrder(String productId)`
- [ ] Method `List<ProductMediaJpaEntity> findBySkuId(String skuId)`
- [ ] Method `void deleteByProductId(String productId)`

---

## 4. Custom Repository Implementations

### 4.1 File: `infrastructure/persistence/repository/ProductJpaRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/repository/ProductJpaRepositoryImpl.java`

**Custom interface:** `ProductJpaRepositoryCustom`

- [ ] Create `ProductJpaRepositoryCustom` interface with method `Page<ProductJpaEntity> findByFilters(String categoryId, String status, String search, Boolean deleted, Pageable pageable)`
- [ ] Implement `ProductJpaRepositoryImpl` with `EntityManager`
- [ ] Use `CriteriaBuilder` for dynamic WHERE clause
- [ ] Build count query separately for pagination
- [ ] Implement `List<ProductJpaEntity> findByCategoryIdAndStatusIn(String categoryId, List<String> statuses)` for FSM batch updates

### 4.2 File: `infrastructure/persistence/repository/SkuJpaRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/repository/SkuJpaRepositoryImpl.java`

**Custom interface:** `SkuJpaRepositoryCustom`

- [ ] Create `SkuJpaRepositoryCustom` interface with method `int sumDisplayQuantityByProductId(String productId)`
- [ ] Implement using `CriteriaBuilder` with `Sum` aggregation
- [ ] Implement `Map<String, Integer> sumDisplayQuantityByProductIds(List<String> productIds)`: batch aggregation for FSM auto-hide checks

### 4.3 File: `infrastructure/persistence/repository/CategoryJpaRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/repository/CategoryJpaRepositoryImpl.java`

**Custom interface:** `CategoryJpaRepositoryCustom`

- [ ] Create `CategoryJpaRepositoryCustom` interface
- [ ] Implement `List<CategoryJpaEntity> findAllDescendants(String categoryId)`: recursive CTE query for category tree
- [ ] Implement `List<String> findAllDescendantIds(String categoryId)`: recursive CTE returning IDs only
- [ ] Implement `List<CategoryJpaEntity> findRootToLeafPath(String categoryId)`: path from root to leaf

---

## 5. Entity-to-Domain Mappers

### 5.1 File: `infrastructure/persistence/mapper/ProductJpaMapper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/mapper/ProductJpaMapper.java`

- [ ] Create `@Component` class `ProductJpaMapper`
- [ ] Method `Product toDomain(ProductJpaEntity entity)`: map all fields, deserialize `attributesJson` to `Map<String, Object>`
- [ ] Method `ProductJpaEntity toEntity(Product product)`: map all fields, serialize `attributes` to `attributesJson`
- [ ] Handle null inputs gracefully
- [ ] Use `ObjectMapper` for JSONB serialization/deserialization

### 5.2 File: `infrastructure/persistence/mapper/SkuJpaMapper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/mapper/SkuJpaMapper.java`

- [ ] Create `@Component` class
- [ ] Method `Sku toDomain(SkuJpaEntity entity)`: map all fields, deserialize `variantAttributesJson`
- [ ] Method `SkuJpaEntity toEntity(Sku sku)`: map all fields, serialize `variantAttributes`
- [ ] Handle `promotionalPrice` as nullable `BigDecimal`

### 5.3 File: `infrastructure/persistence/mapper/CategoryJpaMapper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/mapper/CategoryJpaMapper.java`

- [ ] Create `@Component` class
- [ ] Method `Category toDomain(CategoryJpaEntity entity)`
- [ ] Method `CategoryJpaEntity toEntity(Category category)`

### 5.4 File: `infrastructure/persistence/mapper/MarkdownAppliedJpaMapper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/mapper/MarkdownAppliedJpaMapper.java`

- [ ] Create `@Component` class
- [ ] Method `MarkdownApplied toDomain(MarkdownAppliedJpaEntity entity)`
- [ ] Method `MarkdownAppliedJpaEntity toEntity(MarkdownApplied domain)`

### 5.5 File: `infrastructure/persistence/mapper/OutboxEventJpaMapper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/mapper/OutboxEventJpaMapper.java`

- [ ] Create `@Component` class
- [ ] Method `OutboxEvent toDomain(OutboxEventJpaEntity entity)`
- [ ] Method `OutboxEventJpaEntity toEntity(OutboxEvent domain, String payloadJson)`

### 5.6 File: `infrastructure/persistence/mapper/ProductMediaJpaMapper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/mapper/ProductMediaJpaMapper.java`

- [ ] Create `@Component` class
- [ ] Method `ProductMedia toDomain(ProductMediaJpaEntity entity)`
- [ ] Method `ProductMediaJpaEntity toEntity(ProductMedia domain)`

---

## 6. Port (Interface) Definitions

### 6.1 File: `port/outbound/ProductRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/ProductRepository.java`

- [ ] Define interface mirroring domain operations: `save(Product)`, `findById(ProductId)`, `findBySlug(Slug)`, `existsBySlug(Slug)`, `findByCategoryId(CategoryId, Pageable)`, `findByIdForUpdate(ProductId)`, `findByIdForNoKeyUpdate(ProductId)`, `findByFilters(...)`, `delete(Product)`
- [ ] All methods return domain objects (converted from JPA entities in implementation)

### 6.2 File: `port/outbound/SkuRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/SkuRepository.java`

- [ ] Define interface: `save(Sku)`, `findById(SkuId)`, `findBySkuCode(SkuCode)`, `findByProductId(ProductId)`, `findByIdForUpdate(SkuId)`, `findByIdAndNotDeleted(SkuId)`, `findByIds(List<SkuId>)`, `sumDisplayQuantityByProductId(ProductId)`, `saveAll(List<Sku>)`, `delete(Sku)`

### 6.3 File: `port/outbound/CategoryRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/CategoryRepository.java`

- [ ] Define interface: `save(Category)`, `findById(CategoryId)`, `findBySlug(Slug)`, `existsBySlug(Slug)`, `findChildren(CategoryId)`, `findRootCategories()`, `findAllDescendants(CategoryId)`, `hasChildren(CategoryId)`, `delete(Category)`

### 6.4 File: `port/outbound/MarkdownRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/MarkdownRepository.java`

- [ ] Define interface: `save(MarkdownApplied)`, `findByRuleId(String)`, `findBySkuId(String)`, `findByProductId(ProductId)`, `findExpiredMarkdowns(Instant)`, `deleteByRuleId(String)`, `delete(MarkdownApplied)`

### 6.5 File: `port/outbound/OutboxRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/OutboxRepository.java`

- [ ] Define interface: `save(OutboxEvent)`, `deleteOlderThan(LocalDateTime, int limit)`
- [ ] `saveAll(List<OutboxEvent>)` for batch outbox inserts

### 6.6 File: `port/outbound/ProductMediaRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/ProductMediaRepository.java`

- [ ] Define interface: `save(ProductMedia)`, `findByProductId(String)`, `findBySkuId(String)`, `deleteByProductId(String)`, `saveAll(List<ProductMedia>)`

---

## 7. Repository Implementations

### 7.1 File: `infrastructure/persistence/repository/ProductRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/repository/ProductRepositoryImpl.java`

- [ ] Implement `ProductRepository` with `@Repository`
- [ ] Inject `ProductJpaRepository`, `ProductJpaMapper`
- [ ] Implement all port methods, converting between domain and JPA entities
- [ ] Use `@Transactional(readOnly = true)` for read methods
- [ ] Use `@Transactional` for write methods

### 7.2 File: `infrastructure/persistence/repository/SkuRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/repository/SkuRepositoryImpl.java`

- [ ] Implement `SkuRepository`
- [ ] Inject `SkuJpaRepository`, `SkuJpaMapper`
- [ ] Implement all methods

### 7.3 File: `infrastructure/persistence/repository/CategoryRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/repository/CategoryRepositoryImpl.java`

- [ ] Implement `CategoryRepository`
- [ ] Inject `CategoryJpaRepository`, `CategoryJpaMapper`
- [ ] Implement all methods

### 7.4 File: `infrastructure/persistence/repository/MarkdownRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/repository/MarkdownRepositoryImpl.java`

- [ ] Implement `MarkdownRepository`
- [ ] Inject `MarkdownAppliedJpaRepository`, `MarkdownAppliedJpaMapper`
- [ ] Implement all methods

### 7.5 File: `infrastructure/persistence/repository/OutboxRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/repository/OutboxRepositoryImpl.java`

- [ ] Implement `OutboxRepository`
- [ ] Inject `OutboxEventJpaRepository`, `OutboxEventJpaMapper`
- [ ] Implement `deleteOlderThan` with chunked deletion (LIMIT 10000)

### 7.6 File: `infrastructure/persistence/repository/ProductMediaRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/persistence/repository/ProductMediaRepositoryImpl.java`

- [ ] Implement `ProductMediaRepository`
- [ ] Inject `ProductMediaJpaRepository`, `ProductMediaJpaMapper`

---

## 8. Acceptance Criteria

- [ ] All JPA entities map correctly to PostgreSQL schema (verify with `ddl-auto: validate`)
- [ ] `findWithLockById` uses `SELECT ... FOR UPDATE`
- [ ] `findByIdForNoKeyUpdate` uses `SELECT ... FOR NO KEY UPDATE`
- [ ] Recursive CTE for `findAllDescendants` works correctly for multi-level category trees
- [ ] Entity-to-domain mappers handle null values and JSONB serialization/deserialization
- [ ] All repository implementations are registered as Spring beans
- [ ] Unit tests cover entity-to-domain conversion for all mappers
