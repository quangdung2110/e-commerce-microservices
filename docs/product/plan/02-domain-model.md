# Module 02: Domain Model

> **Status:** Module 2 of 15 - IMPLEMENTED
> **Prerequisites:** Module 01 (project foundation)
> **Dependents:** Module 03 (repository), Module 05 (state machine), Module 06 (services)

---

## 1. Overview

This module defines the pure domain layer: entities, value objects, enums, domain events, and domain exceptions. The domain layer has **zero dependencies** on infrastructure (no Spring annotations, no JPA, no Redis). It contains all business rules and logic.

---

## 2. Domain Entities

### 2.1 File: `domain/entity/Product.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/entity/Product.java`

**Aggregate Root.** Contains business methods for lifecycle management.

- [x] Create class with fields: `id (ProductId)`, `categoryId (CategoryId)`, `name (String)`, `slug (Slug)`, `description (String)`, `status (ProductStatus)`, `isDeleted (boolean)`, `adminHidden (boolean)`, `deletedAt (LocalDateTime)`, `attributes (Map<String, Object>)`, `skus (List<Sku>)`, `createdAt (LocalDateTime)`, `updatedAt (LocalDateTime)`
- [x] Create `initialize()` method: auto-generates ID, sets `status = DRAFT`, sets timestamps
- [x] Create `submit()` method: validates at least one SKU with `originalPrice > 0`, sets `status = ACTIVE`, updates `updatedAt`. Throws `InvalidStateTransitionException` if not DRAFT
- [x] Create `hide()` method: sets `status = HIDDEN`, `adminHidden = true`, updates `updatedAt`. Idempotent if already HIDDEN
- [x] Create `show()` method: validates `SUM(displayQuantity) > 0` across SKUs, sets `status = ACTIVE`, `adminHidden = false`, updates `updatedAt`. Throws `DomainException` if no stock
- [x] Create `autoHideOutOfStock()` method: if all SKUs have `displayQuantity = 0` and `status = ACTIVE`, sets `status = HIDDEN`
- [x] Create `autoReactivateInStock()` method: if any SKU has `displayQuantity > 0`, `status = HIDDEN`, `adminHidden = false`, sets `status = ACTIVE`
- [x] Create `markDeleted()` method: sets `isDeleted = true`, `deletedAt = now`
- [x] Create query methods: `hasValidSkus()`, `isActive()`, `getPrimarySkuId()`, `totalDisplayQuantity()`
- [x] Create `canBeDeleted()`: returns true if status is DRAFT or HIDDEN
- [x] Create `addSku(Sku)` and `removeSku(SkuId)` methods
- [x] Create `recalculateBestPrices()` method that iterates SKUs and sets best price

### 2.2 File: `domain/entity/Sku.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/entity/Sku.java`

**Entity (not aggregate root).** Managed by Product aggregate.

- [x] Create class with fields: `id (SkuId)`, `productId (ProductId)`, `skuCode (SkuCode)`, `originalPrice (BigDecimal)`, `promotionalPrice (BigDecimal)`, `status (SkuStatus)`, `isDeleted (boolean)`, `deletedAt (LocalDateTime)`, `displayQuantity (int)`, `lastInventorySyncAt (long epoch millis)`, `variantAttributes (Map<String, Object>)`, `media (List<ProductMedia>)`, `createdAt (LocalDateTime)`, `updatedAt (LocalDateTime)`
- [x] Create `initialize(ProductId)` method: auto-generate ID, set `status = ACTIVE`, `displayQuantity = 0`
- [x] Create `getBestPrice()` method: return `promotionalPrice` if not null, else `originalPrice`
- [x] Create `hasPromotion()` method: return `promotionalPrice != null`
- [x] Create `isInStock()` method: return `displayQuantity > 0 && status == ACTIVE && !isDeleted`
- [x] Create `isAvailable()` method: return `isInStock() && !isDeleted`
- [x] Create `applyMarkdown(BigDecimal calculatedPrice, String ruleId)` method
- [x] Create `removeMarkdown()` method: set `promotionalPrice = null`
- [x] Create `updateDisplayQuantity(int quantity, long syncTimestamp)` method: only update if `syncTimestamp > lastInventorySyncAt`
- [x] Create `markDeleted()` method: set `isDeleted = true`, `deletedAt = now`

### 2.3 File: `domain/entity/Category.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/entity/Category.java`

**Aggregate Root.**

- [x] Create class with fields: `id (CategoryId)`, `parentId (CategoryId)`, `name (String)`, `slug (Slug)`, `level (int)`, `isLeaf (boolean)`, `attributes (List<CategoryAttribute>)`, `createdAt (LocalDateTime)`, `updatedAt (LocalDateTime)`
- [x] Create `initialize()` method: auto-generate ID, set timestamps
- [x] Create `makeLeaf()` method: sets `isLeaf = true`
- [x] Create `makeNonLeaf()` method: sets `isLeaf = false`
- [x] Create `rename(String newName)` method: updates name and updatedAt
- [x] Create query methods: `isRoot()`, `hasParent()`, `isLeafCategory()`

### 2.4 File: `domain/entity/ProductMedia.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/entity/ProductMedia.java`

- [x] Create class with fields: `id (MediaId)`, `skuId (SkuId)`, `mediaType (MediaType)`, `url (String)`, `thumbnailUrl (String)`, `sortOrder (int)`, `createdAt (LocalDateTime)`, `updatedAt (LocalDateTime)`
- [x] Create `initialize()` method: auto-generate ID, set timestamps

### 2.5 File: `domain/entity/CategoryAttribute.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/entity/CategoryAttribute.java`

- [x] Create class with fields: `id (String)`, `attributeId (String)`, `attributeName (String)`, `attributeType (AttributeType)`, `isRequired (boolean)`, `isVariant (boolean)`, `values (List<String>)`

### 2.6 File: `domain/entity/MarkdownApplied.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/entity/MarkdownApplied.java`

- [x] Create class with fields: `id (String)`, `productId (ProductId)`, `ruleId (String)`, `skuId (SkuId)`, `discountType (DiscountType)`, `discountValue (BigDecimal)`, `maxDiscountAmount (BigDecimal)`, `calculatedPrice (BigDecimal)`, `appliedAt (LocalDateTime)`, `expiresAt (LocalDateTime)`
- [x] Create `isExpired(Instant now)` method
- [x] Create `recalculatePrice(BigDecimal originalPrice)` method: recalculates `calculatedPrice` based on discount type

### 2.7 File: `domain/entity/OutboxEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/entity/OutboxEvent.java`

- [x] Create class with fields: `eventId (UUID)`, `eventType (String)`, `aggregateType (String)`, `aggregateId (String)`, `partitionKey (String)`, `payload (String JSON)`, `createdAt (LocalDateTime)`
- [x] Create factory method `of(DomainEvent event, String aggregateId, String partitionKey, String payloadJson)`

---

## 3. Domain ID Classes (Value Objects)

### 3.1 File: `domain/vo/ProductId.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/vo/ProductId.java`

- [x] Create `final class ProductId implements Serializable`
- [x] Private final field `String value`
- [x] Private constructor: validate non-blank, generate via ULID if null (factory method `generate()`)
- [x] Factory method `of(String value)`: validate and wrap
- [x] Implement `equals()`, `hashCode()`, `toString()`
- [x] Implement `getValue()` accessor

### 3.2 File: `domain/vo/SkuId.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/vo/SkuId.java`

- [x] Same pattern as `ProductId`
- [x] Factory method `generate()` using ULID

### 3.3 File: `domain/vo/CategoryId.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/vo/CategoryId.java`

- [x] Same pattern as `ProductId`
- [x] Factory method `generate()` using ULID

### 3.4 File: `domain/vo/Slug.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/vo/Slug.java`

- [x] Create `final class Slug`
- [x] Validate: non-blank, lowercase alphanumeric with hyphens only, max 255 chars
- [x] Factory method `of(String value)`: normalize to lowercase, trim hyphens
- [x] Implement `toUrl()` method: returns `/category/{slug}`

### 3.5 File: `domain/vo/SkuCode.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/vo/SkuCode.java`

- [x] Create `final class SkuCode`
- [x] Validate: non-blank, alphanumeric with dashes, max 255 chars, unique
- [x] Factory method `of(String value)`

### 3.6 File: `domain/vo/Money.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/vo/Money.java`

- [x] Create `final class Money implements Comparable<Money>`
- [x] Private final `BigDecimal amount`, `Currency currency`
- [x] Constructors: `of(BigDecimal)`, `of(double)`, `zero()`
- [x] Methods: `add(Money)`, `subtract(Money)`, `multiply(double)`, `isPositive()`, `isZero()`, `isGreaterThan(Money)`, `min(Money)`, `max(Money)`
- [x] Implement `compareTo()`, `equals()`, `hashCode()`, `toString()`
- [x] Static factory `cents(long)` for integer cents

---

## 4. Domain Enums

### 4.1 File: `domain/enums/ProductStatus.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/enums/ProductStatus.java`

- [x] Enum: `DRAFT`, `ACTIVE`, `HIDDEN`
- [x] Add `isMutable()`: DRAFT and HIDDEN are mutable, ACTIVE is not directly mutable (use FSM transitions)

### 4.2 File: `domain/enums/SkuStatus.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/enums/SkuStatus.java`

- [x] Enum: `ACTIVE`, `INACTIVE`, `OUT_OF_STOCK`
- [x] Add `isAvailable()`: returns `this == ACTIVE`

### 4.3 File: `domain/enums/DiscountType.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/enums/DiscountType.java`

- [x] Enum: `PERCENTAGE`, `FIXED_AMOUNT`

### 4.4 File: `domain/enums/MediaType.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/enums/MediaType.java`

- [x] Enum: `IMAGE`, `VIDEO`

### 4.5 File: `domain/enums/AttributeType.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/enums/AttributeType.java`

- [x] Enum: `TEXT`, `NUMBER`, `DROPDOWN`

### 4.6 File: `domain/enums/ChangeSource.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/enums/ChangeSource.java`

- [x] Enum values: `ADMIN_SET_INACTIVE`, `ADMIN_SET_ACTIVE`, `ADMIN_UPDATE`, `AUTO_HIDE_OUT_OF_STOCK`, `AUTO_REACTIVATE_IN_STOCK`, `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED`, `INVENTORY_SYNC_PHASE2`

### 4.7 File: `domain/enums/ProductEventType.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/enums/ProductEventType.java`

- [x] Enum: `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_STATUS_CHANGED`, `SKU_CREATED`, `SKU_UPDATED`, `SKU_DELETED`, `PRODUCT_DELETED`, `CATEGORY_CREATED`, `CATEGORY_UPDATED`, `CATEGORY_DELETED`

### 4.8 File: `domain/enums/ChangedField.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/enums/ChangedField.java`

- [x] Enum values: `original_price`, `promotional_price`, `best_price`, `has_promotion`, `sku_status`, `variant_attributes`, `media`, `product_status`, `display_quantity`

---

## 5. Domain Events

### 5.1 File: `domain/event/ProductCreatedEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/event/ProductCreatedEvent.java`

- [x] Extend `DomainEvent`
- [x] Payload fields: `productId`, `name`, `categoryId`, `status`, `skuIds[]`
- [x] Constructor accepting `Product` domain entity

### 5.2 File: `domain/event/ProductUpdatedEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/event/ProductUpdatedEvent.java`

- [x] Extend `DomainEvent`
- [x] Payload fields: `productId`, `changedFields[]`, `skuIds[]`, `updatedAt`

### 5.3 File: `domain/event/ProductStatusChangedEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/event/ProductStatusChangedEvent.java`

- [x] Extend `DomainEvent`
- [x] Payload fields: `productId`, `oldStatus`, `newStatus`, `changedBy`, `changedByType`, `reason`, `skuIds[]`

### 5.4 File: `domain/event/SkuUpdatedEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/event/SkuUpdatedEvent.java`

- [x] Extend `DomainEvent`
- [x] Partition key: `sku_id` (NOT `product_id`)
- [x] Payload fields: `productId`, `skuId`, `skuCode`, `productName`, `categoryId`, `originalPrice`, `promotionalPrice`, `bestPrice`, `hasPromotion`, `skuStatus`, `productStatus`, `isAvailable`, `displayQuantity`, `variantAttributes`, `changedFields[]`, `changeSource`, `updatedAt`

### 5.5 File: `domain/event/SkuCreatedEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/event/SkuCreatedEvent.java`

- [x] Extend `DomainEvent`
- [x] Partition key: `sku_id`
- [x] Payload fields: full SKU snapshot

### 5.6 File: `domain/event/SkuDeletedEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/event/SkuDeletedEvent.java`

- [x] Extend `DomainEvent`
- [x] Payload fields: `productId`, `productName`, `categoryId`, `categoryName`, `skuId`, `skuCode`, `updatedAt`, `deletedAt`

### 5.7 File: `domain/event/ProductDeletedEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/event/ProductDeletedEvent.java`

- [x] Extend `DomainEvent`
- [x] Payload fields: `productId`, `skuIds[]`, `updatedAt`, `deletedAt`

### 5.8 File: `domain/event/CategoryCreatedEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/event/CategoryCreatedEvent.java`

- [x] Extend `DomainEvent`
- [x] Partition key: `category_id`

### 5.9 File: `domain/event/CategoryUpdatedEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/event/CategoryUpdatedEvent.java`

- [x] Extend `DomainEvent`

### 5.10 File: `domain/event/CategoryDeletedEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/event/CategoryDeletedEvent.java`

- [x] Extend `DomainEvent`
- [x] Payload fields: `categoryId`, `affectedProductIds[]`

---

## 6. Domain Exceptions

### 6.1 File: `domain/exception/ProductNotFoundException.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/exception/ProductNotFoundException.java`

- [x] Extend `DomainException`
- [x] Constructor accepting `String productId`
- [x] Message: `"Product not found: {productId}"`

### 6.2 File: `domain/exception/SkuNotFoundException.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/exception/SkuNotFoundException.java`

- [x] Extend `DomainException`
- [x] Constructor accepting `String skuId`
- [x] Message: `"SKU not found: {skuId}"`

### 6.3 File: `domain/exception/CategoryNotFoundException.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/exception/CategoryNotFoundException.java`

- [x] Extend `DomainException`
- [x] Constructor accepting `String categoryId`

### 6.4 File: `domain/exception/InvalidStateTransitionException.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/exception/InvalidStateTransitionException.java`

- [x] Extend `DomainException`
- [x] Constructor accepting `String message`
- [x] Constructor accepting `String fromState`, `String toState`, `String reason`

### 6.5 File: `domain/exception/DuplicateSlugException.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/exception/DuplicateSlugException.java`

- [x] Extend `DomainException`
- [x] Constructor accepting `String slug`

### 6.6 File: `domain/exception/DuplicateSkuCodeException.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/exception/DuplicateSkuCodeException.java`

- [x] Extend `DomainException`
- [x] Constructor accepting `String skuCode`

### 6.7 File: `domain/exception/DomainException.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/exception/DomainException.java`

- [x] Abstract base class extending `RuntimeException`
- [x] Constructor: `message` only
- [x] Constructor: `message` + `cause`

### 6.8 File: `domain/exception/ConcurrencyConflictException.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/exception/ConcurrencyConflictException.java`

- [x] Extend `DomainException`
- [x] Used for optimistic locking failures in Phase 2 inventory sync

### 6.9 File: `domain/exception/ValidationException.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/exception/ValidationException.java`

- [x] Extend `DomainException`
- [x] Constructor accepting `String field`, `String message`
- [x] Add `Map<String, String> errors` field for field-level validation errors

---

## 7. Domain Services

### 7.1 File: `domain/service/MarkdownCalculator.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/service/MarkdownCalculator.java`

- [x] Create `@DomainService` class (marker annotation, no Spring)
- [x] Method `calculate(BigDecimal originalPrice, DiscountType discountType, BigDecimal discountValue, BigDecimal maxDiscountAmount)`: returns `BigDecimal`
- [x] For `PERCENTAGE`: `discountAmount = MIN(originalPrice * discountValue / 100, maxDiscountAmount)`, `promotionalPrice = MAX(0, ROUND(originalPrice - discountAmount, 2))`
- [x] For `FIXED_AMOUNT`: `promotionalPrice = MAX(0, ROUND(originalPrice - discountValue, 2))`
- [x] Add null/negative input validation
- [x] Add `ROUND_HALF_UP` rounding mode

### 7.2 File: `domain/service/DomainServiceMarker.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/service/DomainServiceMarker.java`

- [x] Create `@DomainService` annotation (no Spring, marker for ArchUnit)
- [x] Package-level documentation that this package contains pure domain services

---

## 8. Acceptance Criteria

- [x] All domain entities have zero imports from `infrastructure`, `persistence`, or Spring packages
- [x] All domain exceptions extend `DomainException` (not `RuntimeException` directly)
- [x] Domain events include all required payload fields from `product_events.md`
- [x] `MarkdownCalculator` produces correct results for both `PERCENTAGE` and `FIXED_AMOUNT` discount types
- [ ] Domain entities pass ArchUnit "no layer violation" tests (pending Module 14)
