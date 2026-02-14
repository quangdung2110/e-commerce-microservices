# Implementation Plan: Catalog Services Group

> **Services**: Product Service, Search Service  
> **Priority**: ⭐⭐⭐⭐ HIGH (Core business domain)  
> **Estimated Time**: 3-4 weeks  
> **Dependencies**: Identity Services (for seller validation), Common module

---

## 📋 Table of Contents
1. [Overview & Objectives](#overview--objectives)
2. [Architecture Decisions](#architecture-decisions)
3. [Phase 1: Product Service - Foundation](#phase-1-product-service---foundation)
4. [Phase 2: Product Service - Core Features](#phase-2-product-service---core-features)
5. [Phase 3: Product Service - Advanced Features](#phase-3-product-service---advanced-features)
6. [Phase 4: Search Service - Elasticsearch Setup](#phase-4-search-service---elasticsearch-setup)
7. [Phase 5: Search Service - Search Features](#phase-5-search-service---search-features)
8. [Phase 6: Integration & Optimization](#phase-6-integration--optimization)
9. [AI Agent Task Breakdown](#ai-agent-task-breakdown)
10. [Validation Checklist](#validation-checklist)

---

## Overview & Objectives

### Business Goals
- Enable sellers to list and manage products
- Support complex product variations (SKUs)
- Provide fast and accurate product search
- Handle product categorization and attributes
- Support brand management
- Enable product reviews integration

### Technical Goals
- Event Sourcing for product lifecycle tracking
- CQRS pattern (write to PostgreSQL, read from Elasticsearch)
- Elasticsearch for search and filtering
- Redis for caching product data
- Kafka for product events
- Support for millions of products

### Success Metrics
- Product search response time < 100ms
- Product creation time < 500ms
- Cache hit rate > 85%
- Support 100 concurrent product updates
- Zero data loss on product updates

---

## Architecture Decisions

### 1. Database Strategy
**Decision**: PostgreSQL for Product Service (separate database: `product_db`)

**Schema Design**:
- Normalized structure for product master data
- Denormalized fields for performance (min_price, max_price, total_sold)
- JSONB for flexible attributes
- Full-text search indexes
- Optimistic locking with version field

### 2. Search Strategy
**Decision**: Elasticsearch for Search Service

**Why Elasticsearch**:
- Full-text search with Vietnamese support
- Faceted search (filters, aggregations)
- Fuzzy matching and typo tolerance
- Real-time indexing
- Horizontal scalability

### 3. CQRS Implementation
```
┌─────────────────────────────────────────────────────────┐
│              WRITE PATH (Product Service)                │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  Seller → CreateProduct → Validate → Save to PostgreSQL │
│                              ↓                           │
│                      Publish Event to Kafka             │
│                                                          │
└───────────────────────────┬─────────────────────────────┘
                            │
                            │ ProductCreatedEvent
                            ↓
┌─────────────────────────────────────────────────────────┐
│               READ PATH (Search Service)                 │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  Kafka Consumer → Transform → Index to Elasticsearch    │
│                                                          │
│  User → Search Request → Query Elasticsearch → Response │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### 4. Caching Strategy
**Redis Cache Layers**:
1. **Product detail cache**: `product:{productId}` (TTL: 1 hour)
2. **Category tree cache**: `category:tree` (TTL: 24 hours)
3. **Brand list cache**: `brands:active` (TTL: 12 hours)
4. **Seller products cache**: `seller:{sellerId}:products` (TTL: 30 min)
5. **Hot products cache**: `products:featured` (TTL: 15 min)

---

## Phase 1: Product Service - Foundation

### 1.1 Database Setup (Agent Task: DB-PRODUCT-001)

**Objective**: Create `product_db` with all tables

**Tables in Order**:
```sql
-- 1. Reference data (no FK dependencies)
CREATE TABLE brands (...);
CREATE TABLE categories (...);
CREATE TABLE attributes (...);
CREATE TABLE attribute_values (...);

-- 2. Junction tables
CREATE TABLE category_attributes (...);

-- 3. Product core
CREATE TABLE products (...);
CREATE TABLE product_skus (...);
CREATE TABLE product_attributes (...);
CREATE TABLE product_media (...);
```

**Important Indexes**:
```sql
-- Full-text search
CREATE INDEX idx_products_search 
ON products USING gin(to_tsvector('vietnamese', name || ' ' || COALESCE(short_description, '')));

-- Category hierarchy
CREATE INDEX idx_categories_path 
ON categories USING GIST (path gist_trgm_ops);

-- Price range filtering
CREATE INDEX idx_products_price 
ON products(min_price, max_price) WHERE status = 'ACTIVE';

-- Best seller
CREATE INDEX idx_products_featured 
ON products(is_featured, total_sold DESC) WHERE status = 'ACTIVE';
```

**Deliverables**:
- `V1__create_product_schema.sql`
- `V1__seed_categories.sql` (pre-populate category tree)
- `V1__seed_attributes.sql` (common attributes like color, size)

**Validation**:
```sql
-- Verify foreign keys
SELECT 
    tc.constraint_name, 
    tc.table_name, 
    kcu.column_name, 
    ccu.table_name AS foreign_table_name,
    ccu.column_name AS foreign_column_name 
FROM information_schema.table_constraints AS tc 
JOIN information_schema.key_column_usage AS kcu
    ON tc.constraint_name = kcu.constraint_name
JOIN information_schema.constraint_column_usage AS ccu
    ON ccu.constraint_name = tc.constraint_name
WHERE tc.constraint_type = 'FOREIGN KEY';
```

---

### 1.2 Domain Model (Agent Task: PRODUCT-DOMAIN-001)

**Objective**: Create JPA entities with validation

**Key Entities**:

```java
@Entity
@Table(name = "products")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {
    @Id
    private UUID productId;
    
    private UUID sellerId;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;
    
    @Column(nullable = false, length = 500)
    @NotBlank(message = "Product name is required")
    @Size(min = 10, max = 500, message = "Name must be between 10-500 characters")
    private String name;
    
    @Column(nullable = false, length = 500)
    private String slug;
    
    @Column(length = 1000)
    private String shortDescription;
    
    @Column(columnDefinition = "TEXT")
    private String description;
    
    @Column(nullable = false, precision = 15, scale = 2)
    @DecimalMin(value = "0.0", message = "Price must be positive")
    private BigDecimal minPrice;
    
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal maxPrice;
    
    @Column(precision = 15, scale = 2)
    private BigDecimal originalMinPrice;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;
    
    @Column(columnDefinition = "TEXT")
    private String rejectionReason;
    
    private boolean isFeatured;
    private boolean isNewArrival;
    private boolean isBestSeller;
    
    private int totalSold;
    private int totalViews;
    private int totalFavorites;
    
    @Column(precision = 3, scale = 2)
    private BigDecimal avgRating;
    
    private int reviewCount;
    
    @Column(length = 200)
    private String seoTitle;
    
    @Column(columnDefinition = "TEXT")
    private String seoDescription;
    
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "TEXT[]")
    private List<String> seoKeywords;
    
    @Column(precision = 10, scale = 3)
    private BigDecimal weightKg;
    
    @Column(precision = 10, scale = 2)
    private BigDecimal lengthCm;
    
    @Column(precision = 10, scale = 2)
    private BigDecimal widthCm;
    
    @Column(precision = 10, scale = 2)
    private BigDecimal heightCm;
    
    private Instant deletedAt;
    
    @CreatedDate
    private Instant createdAt;
    
    @LastModifiedDate
    private Instant updatedAt;
    
    private Instant publishedAt;
    
    @Version
    private Integer version;
    
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductSku> skus = new ArrayList<>();
    
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductMedia> media = new ArrayList<>();
    
    // Helper methods
    public void addSku(ProductSku sku) {
        skus.add(sku);
        sku.setProduct(this);
    }
    
    public void removeSku(ProductSku sku) {
        skus.remove(sku);
        sku.setProduct(null);
    }
    
    public void recalculatePriceRange() {
        if (skus.isEmpty()) return;
        
        this.minPrice = skus.stream()
            .filter(ProductSku::isActive)
            .map(ProductSku::getPrice)
            .min(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
        
        this.maxPrice = skus.stream()
            .filter(ProductSku::isActive)
            .map(ProductSku::getPrice)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    }
}

@Entity
@Table(name = "product_skus")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductSku {
    @Id
    private UUID skuId;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;
    
    @Column(nullable = false, length = 100)
    private String skuCode;
    
    @Column(length = 100)
    private String barcode;
    
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, VariantAttribute> variantAttributes;
    
    @Column(nullable = false, precision = 15, scale = 2)
    @DecimalMin(value = "0.0")
    private BigDecimal price;
    
    @Column(precision = 15, scale = 2)
    private BigDecimal originalPrice;
    
    @Column(precision = 15, scale = 2)
    private BigDecimal costPrice;
    
    private int stockQuantity;
    private int reservedQuantity;
    
    private boolean isActive;
    private boolean isDefault;
    
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "TEXT[]")
    private List<String> imageUrls;
    
    @CreatedDate
    private Instant createdAt;
    
    @LastModifiedDate
    private Instant updatedAt;
    
    @Version
    private Integer version;
}

@Entity
@Table(name = "categories")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {
    @Id
    private UUID categoryId;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;
    
    @Column(nullable = false, length = 200)
    private String name;
    
    @Column(nullable = false, unique = true, length = 200)
    private String slug;
    
    @Column(columnDefinition = "TEXT")
    private String description;
    
    private String iconUrl;
    private String imageUrl;
    
    @Column(nullable = false)
    private short level;
    
    @Column(nullable = false)
    private String path; // Materialized path: "1/5/23"
    
    private int displayOrder;
    private boolean isActive;
    private boolean isFeatured;
    
    @Column(length = 200)
    private String seoTitle;
    
    @Column(columnDefinition = "TEXT")
    private String seoDescription;
    
    private int productCount;
    
    @CreatedDate
    private Instant createdAt;
    
    @LastModifiedDate
    private Instant updatedAt;
    
    @Version
    private Integer version;
    
    @OneToMany(mappedBy = "parent")
    private List<Category> children = new ArrayList<>();
    
    // Helper method to build full category path
    public String getFullPath() {
        if (parent == null) {
            return name;
        }
        return parent.getFullPath() + " > " + name;
    }
}
```

**Value Objects**:
```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VariantAttribute {
    private String value;
    private String display;
    
    // Example: {"value": "red", "display": "Đỏ"}
}
```

**Enums**:
```java
public enum ProductStatus {
    DRAFT,
    PENDING_REVIEW,
    ACTIVE,
    INACTIVE,
    DISCONTINUED,
    REJECTED
}

public enum AttributeDataType {
    STRING,
    NUMBER,
    BOOLEAN,
    DATE,
    SELECT,
    MULTI_SELECT
}
```

**Deliverables**:
- All JPA entities with proper annotations
- Repository interfaces
- Validation constraints
- Enums and value objects

---

### 1.3 Domain Events (Agent Task: PRODUCT-EVENT-001)

**Objective**: Define all product lifecycle events

**Events**:

```java
@SuperBuilder
@Data
@EqualsAndHashCode(callSuper = true)
@DomainEvent(aggregateType = "Product")
public class ProductCreatedEvent extends BaseEvent {
    private String productId;
    private String sellerId;
    private String name;
    private String categoryId;
    private String brandId;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private List<ProductSkuDto> skus;
    private List<ProductMediaDto> media;
    private Map<String, Object> attributes;
}

@DomainEvent(aggregateType = "Product")
public class ProductUpdatedEvent extends BaseEvent {
    private String productId;
    private String sellerId;
    private Map<String, Object> changedFields;
    private Integer previousVersion;
}

@DomainEvent(aggregateType = "Product")
public class ProductStatusChangedEvent extends BaseEvent {
    private String productId;
    private String sellerId;
    private String previousStatus;
    private String newStatus;
    private String reason;
    private String changedBy;
}

@DomainEvent(aggregateType = "Product")
public class ProductPriceChangedEvent extends BaseEvent {
    private String productId;
    private String sellerId;
    private String skuId;
    private BigDecimal previousPrice;
    private BigDecimal newPrice;
    private String reason; // MANUAL, PROMOTION, FLASH_SALE, COST_CHANGE
}

@DomainEvent(aggregateType = "Product")
public class ProductStockUpdatedEvent extends BaseEvent {
    private String productId;
    private String sellerId;
    private String skuId;
    private Integer previousQuantity;
    private Integer newQuantity;
    private String reason; // SALE, RESTOCK, ADJUSTMENT, RETURN, DAMAGE
}

@DomainEvent(aggregateType = "Product")
public class ProductViewedEvent extends BaseEvent {
    private String productId;
    private String sellerId;
    private String userId;
    private String sessionId;
    private String source; // SEARCH, RECOMMENDATION, DIRECT, CATEGORY
    private String referrer;
}

@DomainEvent(aggregateType = "Product")
public class ProductDeletedEvent extends BaseEvent {
    private String productId;
    private String sellerId;
    private String deletedBy;
    private String reason;
}

@DomainEvent(aggregateType = "Product")
public class ProductPublishedEvent extends BaseEvent {
    private String productId;
    private String sellerId;
    private Instant publishedAt;
}

@DomainEvent(aggregateType = "Product")
public class ProductMediaAddedEvent extends BaseEvent {
    private String productId;
    private String sellerId;
    private String mediaId;
    private String mediaType;
    private String storageKey;
    private boolean isPrimary;
}

@DomainEvent(aggregateType = "Product")
public class SkuAddedEvent extends BaseEvent {
    private String productId;
    private String sellerId;
    private String skuId;
    private String skuCode;
    private Map<String, VariantAttribute> variantAttributes;
    private BigDecimal price;
    private Integer stockQuantity;
}

@DomainEvent(aggregateType = "Category")
public class CategoryCreatedEvent extends BaseEvent {
    private String categoryId;
    private String parentId;
    private String name;
    private String slug;
    private Integer level;
}

@DomainEvent(aggregateType = "Brand")
public class BrandCreatedEvent extends BaseEvent {
    private String brandId;
    private String name;
    private String slug;
    private boolean isOfficial;
}
```

**Deliverables**:
- All event classes
- DTOs for nested objects (ProductSkuDto, ProductMediaDto)
- Event publisher implementation

---

## Phase 2: Product Service - Core Features

### 2.1 Aggregate Implementation (Agent Task: PRODUCT-AGGREGATE-001)

**Objective**: Implement Product aggregate with business logic

**ProductAggregate**:

```java
@Aggregate
@Data
public class ProductAggregate {
    private String productId;
    private String sellerId;
    private String name;
    private String categoryId;
    private String brandId;
    private ProductStatus status;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private Map<String, ProductSkuAggregate> skus = new HashMap<>();
    private List<ProductMediaAggregate> media = new ArrayList<>();
    private int version;
    
    private List<BaseEvent> uncommittedEvents = new ArrayList<>();
    
    // Command handlers
    public void createProduct(CreateProductCommand cmd) {
        // Validation
        validateCreateProduct(cmd);
        
        // Calculate price range
        BigDecimal minPrice = cmd.getSkus().stream()
            .map(ProductSkuDto::getPrice)
            .min(BigDecimal::compareTo)
            .orElseThrow(() -> new IllegalArgumentException("At least one SKU required"));
        
        BigDecimal maxPrice = cmd.getSkus().stream()
            .map(ProductSkuDto::getPrice)
            .max(BigDecimal::compareTo)
            .get();
        
        // Apply event
        apply(ProductCreatedEvent.builder()
            .aggregateId(productId)
            .productId(productId)
            .sellerId(cmd.getSellerId())
            .name(cmd.getName())
            .categoryId(cmd.getCategoryId())
            .brandId(cmd.getBrandId())
            .minPrice(minPrice)
            .maxPrice(maxPrice)
            .skus(cmd.getSkus())
            .media(cmd.getMedia())
            .attributes(cmd.getAttributes())
            .build());
    }
    
    public void updatePrice(String skuId, BigDecimal newPrice, String reason) {
        ProductSkuAggregate sku = skus.get(skuId);
        if (sku == null) {
            throw new SkuNotFoundException("SKU not found: " + skuId);
        }
        
        if (newPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidPriceException("Price must be positive");
        }
        
        BigDecimal previousPrice = sku.getPrice();
        
        apply(ProductPriceChangedEvent.builder()
            .aggregateId(productId)
            .productId(productId)
            .sellerId(sellerId)
            .skuId(skuId)
            .previousPrice(previousPrice)
            .newPrice(newPrice)
            .reason(reason)
            .build());
    }
    
    public void updateStock(String skuId, int newQuantity, String reason) {
        ProductSkuAggregate sku = skus.get(skuId);
        if (sku == null) {
            throw new SkuNotFoundException("SKU not found: " + skuId);
        }
        
        if (newQuantity < 0) {
            throw new InvalidStockException("Stock cannot be negative");
        }
        
        int previousQuantity = sku.getStockQuantity();
        
        apply(ProductStockUpdatedEvent.builder()
            .aggregateId(productId)
            .productId(productId)
            .sellerId(sellerId)
            .skuId(skuId)
            .previousQuantity(previousQuantity)
            .newQuantity(newQuantity)
            .reason(reason)
            .build());
    }
    
    public void changeStatus(ProductStatus newStatus, String reason, String changedBy) {
        // Validate state transition
        if (!canTransitionTo(this.status, newStatus)) {
            throw new InvalidStatusTransitionException(
                String.format("Cannot transition from %s to %s", this.status, newStatus)
            );
        }
        
        apply(ProductStatusChangedEvent.builder()
            .aggregateId(productId)
            .productId(productId)
            .sellerId(sellerId)
            .previousStatus(this.status.name())
            .newStatus(newStatus.name())
            .reason(reason)
            .changedBy(changedBy)
            .build());
    }
    
    public void publish() {
        if (this.status != ProductStatus.ACTIVE) {
            throw new IllegalStateException("Product must be ACTIVE to publish");
        }
        
        if (skus.isEmpty()) {
            throw new IllegalStateException("Product must have at least one SKU");
        }
        
        if (media.isEmpty()) {
            throw new IllegalStateException("Product must have at least one image");
        }
        
        apply(ProductPublishedEvent.builder()
            .aggregateId(productId)
            .productId(productId)
            .sellerId(sellerId)
            .publishedAt(Instant.now())
            .build());
    }
    
    public void addSku(AddSkuCommand cmd) {
        // Validate variant uniqueness
        boolean exists = skus.values().stream()
            .anyMatch(sku -> sku.getVariantAttributes().equals(cmd.getVariantAttributes()));
        
        if (exists) {
            throw new DuplicateSkuException("SKU with same variants already exists");
        }
        
        apply(SkuAddedEvent.builder()
            .aggregateId(productId)
            .productId(productId)
            .sellerId(sellerId)
            .skuId(cmd.getSkuId())
            .skuCode(cmd.getSkuCode())
            .variantAttributes(cmd.getVariantAttributes())
            .price(cmd.getPrice())
            .stockQuantity(cmd.getStockQuantity())
            .build());
    }
    
    public void recordView(String userId, String sessionId, String source) {
        apply(ProductViewedEvent.builder()
            .aggregateId(productId)
            .productId(productId)
            .sellerId(sellerId)
            .userId(userId)
            .sessionId(sessionId)
            .source(source)
            .build());
    }
    
    // Event handlers (apply methods)
    private void apply(ProductCreatedEvent event) {
        this.productId = event.getProductId();
        this.sellerId = event.getSellerId();
        this.name = event.getName();
        this.categoryId = event.getCategoryId();
        this.brandId = event.getBrandId();
        this.minPrice = event.getMinPrice();
        this.maxPrice = event.getMaxPrice();
        this.status = ProductStatus.DRAFT;
        this.version = 1;
        
        event.getSkus().forEach(skuDto -> {
            ProductSkuAggregate sku = ProductSkuAggregate.from(skuDto);
            this.skus.put(sku.getSkuId(), sku);
        });
        
        event.initializeEvent();
        uncommittedEvents.add(event);
    }
    
    private void apply(ProductPriceChangedEvent event) {
        ProductSkuAggregate sku = skus.get(event.getSkuId());
        sku.setPrice(event.getNewPrice());
        
        // Recalculate min/max prices
        recalculatePriceRange();
        
        event.initializeEvent();
        uncommittedEvents.add(event);
    }
    
    private void apply(ProductStockUpdatedEvent event) {
        ProductSkuAggregate sku = skus.get(event.getSkuId());
        sku.setStockQuantity(event.getNewQuantity());
        
        event.initializeEvent();
        uncommittedEvents.add(event);
    }
    
    private void apply(ProductStatusChangedEvent event) {
        this.status = ProductStatus.valueOf(event.getNewStatus());
        
        event.initializeEvent();
        uncommittedEvents.add(event);
    }
    
    // Helper methods
    private void validateCreateProduct(CreateProductCommand cmd) {
        if (cmd.getName() == null || cmd.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Product name is required");
        }
        
        if (cmd.getName().length() < 10 || cmd.getName().length() > 500) {
            throw new IllegalArgumentException("Product name must be 10-500 characters");
        }
        
        if (cmd.getSkus() == null || cmd.getSkus().isEmpty()) {
            throw new IllegalArgumentException("At least one SKU is required");
        }
        
        if (cmd.getCategoryId() == null) {
            throw new IllegalArgumentException("Category is required");
        }
    }
    
    private boolean canTransitionTo(ProductStatus from, ProductStatus to) {
        // Define allowed transitions
        Map<ProductStatus, List<ProductStatus>> transitions = Map.of(
            ProductStatus.DRAFT, List.of(ProductStatus.PENDING_REVIEW, ProductStatus.ACTIVE),
            ProductStatus.PENDING_REVIEW, List.of(ProductStatus.ACTIVE, ProductStatus.REJECTED),
            ProductStatus.ACTIVE, List.of(ProductStatus.INACTIVE, ProductStatus.DISCONTINUED),
            ProductStatus.INACTIVE, List.of(ProductStatus.ACTIVE),
            ProductStatus.REJECTED, List.of(ProductStatus.DRAFT)
        );
        
        return transitions.getOrDefault(from, Collections.emptyList()).contains(to);
    }
    
    private void recalculatePriceRange() {
        this.minPrice = skus.values().stream()
            .filter(sku -> sku.isActive())
            .map(ProductSkuAggregate::getPrice)
            .min(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
        
        this.maxPrice = skus.values().stream()
            .filter(sku -> sku.isActive())
            .map(ProductSkuAggregate::getPrice)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    }
    
    public List<BaseEvent> getUncommittedEvents() {
        return new ArrayList<>(uncommittedEvents);
    }
    
    public void markEventsAsCommitted() {
        uncommittedEvents.clear();
    }
}
```

**Deliverables**:
- `ProductAggregate.java`
- `ProductSkuAggregate.java`
- Command classes
- State transition validation

---

### 2.2 Service Layer (Agent Task: PRODUCT-SERVICE-001)

**Objective**: Implement service layer with Event Sourcing

**ProductService**:

```java
@Service
@RequiredArgsConstructor
@Transactional
public class ProductService {
    private final ProductRepository productRepository;
    private final ProductSkuRepository skuRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final ProductEventStore eventStore;
    private final ProductEventPublisher eventPublisher;
    private final RedisService redisService;
    
    public ProductDto createProduct(CreateProductCommand cmd) {
        // 1. Validate seller exists (call Seller Service)
        validateSeller(cmd.getSellerId());
        
        // 2. Validate category exists
        Category category = categoryRepository.findById(UUID.fromString(cmd.getCategoryId()))
            .orElseThrow(() -> new CategoryNotFoundException());
        
        // 3. Create aggregate
        ProductAggregate aggregate = new ProductAggregate();
        aggregate.setProductId(UUID.randomUUID().toString());
        aggregate.createProduct(cmd);
        
        // 4. Save events to event store
        eventStore.saveEvents(aggregate.getProductId(), aggregate.getUncommittedEvents());
        
        // 5. Save to read model (PostgreSQL)
        Product product = mapToEntity(aggregate);
        productRepository.save(product);
        
        // 6. Publish events to Kafka
        aggregate.getUncommittedEvents().forEach(eventPublisher::publish);
        aggregate.markEventsAsCommitted();
        
        // 7. Cache product
        ProductDto dto = ProductDto.from(product);
        cacheProduct(dto);
        
        return dto;
    }
    
    public ProductDto getProductById(UUID productId) {
        // Try cache first
        String cacheKey = "product:" + productId;
        Optional<ProductDto> cached = redisService.get(cacheKey, ProductDto.class);
        
        if (cached.isPresent()) {
            return cached.get();
        }
        
        // Load from database
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new ProductNotFoundException());
        
        ProductDto dto = ProductDto.from(product);
        cacheProduct(dto);
        
        return dto;
    }
    
    public ProductDto updatePrice(UUID productId, UUID skuId, BigDecimal newPrice, String reason) {
        // 1. Load aggregate from events
        ProductAggregate aggregate = loadAggregate(productId.toString());
        
        // 2. Execute command
        aggregate.updatePrice(skuId.toString(), newPrice, reason);
        
        // 3. Save events
        eventStore.saveEvents(aggregate.getProductId(), aggregate.getUncommittedEvents());
        
        // 4. Update read model
        ProductSku sku = skuRepository.findById(skuId)
            .orElseThrow(() -> new SkuNotFoundException());
        sku.setPrice(newPrice);
        sku.setUpdatedAt(Instant.now());
        skuRepository.save(sku);
        
        // Update product min/max price
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new ProductNotFoundException());
        product.recalculatePriceRange();
        productRepository.save(product);
        
        // 5. Publish events
        aggregate.getUncommittedEvents().forEach(eventPublisher::publish);
        aggregate.markEventsAsCommitted();
        
        // 6. Invalidate cache
        redisService.delete("product:" + productId);
        
        return ProductDto.from(product);
    }
    
    public void recordView(UUID productId, String userId, String sessionId, String source) {
        // Load aggregate
        ProductAggregate aggregate = loadAggregate(productId.toString());
        
        // Record view
        aggregate.recordView(userId, sessionId, source);
        
        // Save event
        eventStore.saveEvents(aggregate.getProductId(), aggregate.getUncommittedEvents());
        
        // Publish event (for analytics)
        aggregate.getUncommittedEvents().forEach(eventPublisher::publish);
        aggregate.markEventsAsCommitted();
        
        // Update view count in read model (async via event listener)
    }
    
    public Page<ProductDto> getSellerProducts(UUID sellerId, Pageable pageable) {
        // Try cache for first page
        if (pageable.getPageNumber() == 0) {
            String cacheKey = "seller:" + sellerId + ":products";
            Optional<Page<ProductDto>> cached = redisService.get(cacheKey, new TypeReference<>() {});
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        
        Page<Product> products = productRepository.findBySellerIdAndStatusIn(
            sellerId,
            List.of(ProductStatus.ACTIVE, ProductStatus.INACTIVE),
            pageable
        );
        
        Page<ProductDto> result = products.map(ProductDto::from);
        
        // Cache first page only
        if (pageable.getPageNumber() == 0) {
            redisService.set(
                "seller:" + sellerId + ":products",
                result,
                Duration.ofMinutes(30)
            );
        }
        
        return result;
    }
    
    // Load aggregate from event store
    private ProductAggregate loadAggregate(String productId) {
        List<BaseEvent> events = eventStore.getEvents(productId);
        
        if (events.isEmpty()) {
            throw new ProductNotFoundException("Product not found: " + productId);
        }
        
        ProductAggregate aggregate = new ProductAggregate();
        aggregate.setProductId(productId);
        
        // Replay events
        events.forEach(event -> {
            if (event instanceof ProductCreatedEvent) {
                aggregate.apply((ProductCreatedEvent) event);
            } else if (event instanceof ProductPriceChangedEvent) {
                aggregate.apply((ProductPriceChangedEvent) event);
            } else if (event instanceof ProductStockUpdatedEvent) {
                aggregate.apply((ProductStockUpdatedEvent) event);
            }
            // ... other events
        });
        
        return aggregate;
    }
    
    private void cacheProduct(ProductDto product) {
        redisService.set(
            "product:" + product.getProductId(),
            product,
            Duration.ofHours(1)
        );
    }
    
    private Product mapToEntity(ProductAggregate aggregate) {
        Product product = Product.builder()
            .productId(UUID.fromString(aggregate.getProductId()))
            .sellerId(UUID.fromString(aggregate.getSellerId()))
            .name(aggregate.getName())
            .minPrice(aggregate.getMinPrice())
            .maxPrice(aggregate.getMaxPrice())
            .status(aggregate.getStatus())
            .createdAt(Instant.now())
            .version(1)
            .build();
        
        // Map SKUs
        aggregate.getSkus().values().forEach(skuAgg -> {
            ProductSku sku = mapSkuToEntity(skuAgg);
            product.addSku(sku);
        });
        
        return product;
    }
}
```

**Deliverables**:
- `ProductService.java`
- `CategoryService.java`
- `BrandService.java`
- `AttributeService.java`
- Event store implementation
- Mapper utilities

---

### 2.3 REST API (Agent Task: PRODUCT-API-001)

**Objective**: Create RESTful endpoints

**ProductController**:

```java
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Validated
public class ProductController {
    private final ProductService productService;
    
    @PostMapping
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<ProductDto> createProduct(
        @Valid @RequestBody CreateProductRequest request,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        CreateProductCommand cmd = CreateProductCommand.builder()
            .sellerId(principal.getSellerId())
            .name(request.getName())
            .categoryId(request.getCategoryId())
            .brandId(request.getBrandId())
            .description(request.getDescription())
            .skus(request.getSkus())
            .media(request.getMedia())
            .attributes(request.getAttributes())
            .build();
        
        ProductDto product = productService.createProduct(cmd);
        
        return ResponseEntity
            .created(URI.create("/api/products/" + product.getProductId()))
            .body(product);
    }
    
    @GetMapping("/{productId}")
    public ResponseEntity<ProductDto> getProduct(@PathVariable UUID productId) {
        ProductDto product = productService.getProductById(productId);
        return ResponseEntity.ok(product);
    }
    
    @PutMapping("/{productId}/skus/{skuId}/price")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<ProductDto> updatePrice(
        @PathVariable UUID productId,
        @PathVariable UUID skuId,
        @Valid @RequestBody UpdatePriceRequest request,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        // Verify ownership
        productService.verifyOwnership(productId, principal.getSellerId());
        
        ProductDto product = productService.updatePrice(
            productId,
            skuId,
            request.getNewPrice(),
            request.getReason()
        );
        
        return ResponseEntity.ok(product);
    }
    
    @PostMapping("/{productId}/view")
    public ResponseEntity<Void> recordView(
        @PathVariable UUID productId,
        @AuthenticationPrincipal UserPrincipal principal,
        HttpServletRequest request
    ) {
        String userId = principal != null ? principal.getUserId().toString() : null;
        String sessionId = request.getSession().getId();
        String source = request.getParameter("source");
        
        productService.recordView(productId, userId, sessionId, source);
        
        return ResponseEntity.accepted().build();
    }
    
    @GetMapping("/seller/{sellerId}")
    public ResponseEntity<Page<ProductDto>> getSellerProducts(
        @PathVariable UUID sellerId,
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<ProductDto> products = productService.getSellerProducts(sellerId, pageable);
        return ResponseEntity.ok(products);
    }
    
    @PatchMapping("/{productId}/status")
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<ProductDto> changeStatus(
        @PathVariable UUID productId,
        @Valid @RequestBody ChangeStatusRequest request,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        productService.verifyOwnership(productId, principal.getSellerId());
        
        ProductDto product = productService.changeStatus(
            productId,
            ProductStatus.valueOf(request.getNewStatus()),
            request.getReason(),
            principal.getUserId().toString()
        );
        
        return ResponseEntity.ok(product);
    }
}
```

**CategoryController**:

```java
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {
    private final CategoryService categoryService;
    
    @GetMapping
    public ResponseEntity<List<CategoryDto>> getAllCategories() {
        // Returns tree structure
        List<CategoryDto> categories = categoryService.getCategoryTree();
        return ResponseEntity.ok(categories);
    }
    
    @GetMapping("/{categoryId}")
    public ResponseEntity<CategoryDto> getCategory(@PathVariable UUID categoryId) {
        CategoryDto category = categoryService.getCategoryById(categoryId);
        return ResponseEntity.ok(category);
    }
    
    @GetMapping("/{categoryId}/products")
    public ResponseEntity<Page<ProductDto>> getCategoryProducts(
        @PathVariable UUID categoryId,
        @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<ProductDto> products = categoryService.getCategoryProducts(categoryId, pageable);
        return ResponseEntity.ok(products);
    }
}
```

**Deliverables**:
- `ProductController.java`
- `CategoryController.java`
- `BrandController.java`
- All request/response DTOs
- Exception handlers
- API documentation (Swagger annotations)

---

## Phase 3: Product Service - Advanced Features

### 3.1 Bulk Operations (Agent Task: PRODUCT-BULK-001)

**Objective**: Support bulk product operations for sellers

**Features**:
- Bulk price update
- Bulk status change
- Bulk import from CSV/Excel
- Bulk export

**BulkOperationService**:

```java
@Service
@RequiredArgsConstructor
public class BulkOperationService {
    private final ProductService productService;
    private final KafkaTemplate<String, BulkOperationEvent> kafkaTemplate;
    
    @Async
    public CompletableFuture<BulkOperationResult> bulkUpdatePrices(
        UUID sellerId,
        List<BulkPriceUpdate> updates
    ) {
        BulkOperationResult result = new BulkOperationResult();
        result.setOperationId(UUID.randomUUID());
        result.setTotalItems(updates.size());
        
        updates.forEach(update -> {
            try {
                productService.updatePrice(
                    update.getProductId(),
                    update.getSkuId(),
                    update.getNewPrice(),
                    "BULK_UPDATE"
                );
                result.incrementSuccess();
            } catch (Exception e) {
                result.addError(update.getProductId(), e.getMessage());
            }
        });
        
        // Publish completion event
        kafkaTemplate.send("bulk-operation-events", 
            BulkOperationCompletedEvent.builder()
                .operationId(result.getOperationId().toString())
                .sellerId(sellerId.toString())
                .operationType("PRICE_UPDATE")
                .totalItems(result.getTotalItems())
                .successCount(result.getSuccessCount())
                .failureCount(result.getFailureCount())
                .build()
        );
        
        return CompletableFuture.completedFuture(result);
    }
    
    @Async
    public CompletableFuture<BulkOperationResult> importProducts(
        UUID sellerId,
        MultipartFile file
    ) {
        // Parse CSV/Excel
        List<CreateProductCommand> commands = parseImportFile(file);
        
        BulkOperationResult result = new BulkOperationResult();
        result.setOperationId(UUID.randomUUID());
        result.setTotalItems(commands.size());
        
        commands.forEach(cmd -> {
            try {
                cmd.setSellerId(sellerId.toString());
                productService.createProduct(cmd);
                result.incrementSuccess();
            } catch (Exception e) {
                result.addError(cmd.getName(), e.getMessage());
            }
        });
        
        return CompletableFuture.completedFuture(result);
    }
}
```

**Deliverables**:
- `BulkOperationService.java`
- CSV/Excel parser
- Async operation tracking
- Progress notification

---

### 3.2 Product Variants Management (Agent Task: PRODUCT-VARIANT-001)

**Objective**: Advanced variant generation and management

**Features**:
- Auto-generate SKU combinations from attributes
- Variant matrix UI support
- Bulk variant operations

**VariantService**:

```java
@Service
@RequiredArgsConstructor
public class VariantService {
    
    public List<ProductSkuDto> generateVariants(GenerateVariantsCommand cmd) {
        // Input: { "color": ["red", "blue"], "size": ["S", "M", "L"] }
        // Output: 6 SKUs (red-S, red-M, red-L, blue-S, blue-M, blue-L)
        
        List<ProductSkuDto> skus = new ArrayList<>();
        List<Map<String, String>> combinations = generateCombinations(cmd.getAttributes());
        
        combinations.forEach(combo -> {
            ProductSkuDto sku = ProductSkuDto.builder()
                .skuId(UUID.randomUUID().toString())
                .skuCode(generateSkuCode(cmd.getProductCode(), combo))
                .variantAttributes(combo)
                .price(cmd.getBasePrice())
                .stockQuantity(0)
                .isActive(true)
                .build();
            
            skus.add(sku);
        });
        
        return skus;
    }
    
    private List<Map<String, String>> generateCombinations(
        Map<String, List<String>> attributes
    ) {
        List<Map<String, String>> result = new ArrayList<>();
        generateCombinationsRecursive(
            attributes,
            new ArrayList<>(attributes.keySet()),
            0,
            new HashMap<>(),
            result
        );
        return result;
    }
    
    private void generateCombinationsRecursive(
        Map<String, List<String>> attributes,
        List<String> attributeKeys,
        int index,
        Map<String, String> current,
        List<Map<String, String>> result
    ) {
        if (index == attributeKeys.size()) {
            result.add(new HashMap<>(current));
            return;
        }
        
        String key = attributeKeys.get(index);
        List<String> values = attributes.get(key);
        
        for (String value : values) {
            current.put(key, value);
            generateCombinationsRecursive(attributes, attributeKeys, index + 1, current, result);
            current.remove(key);
        }
    }
    
    private String generateSkuCode(String productCode, Map<String, String> variants) {
        StringBuilder sb = new StringBuilder(productCode);
        variants.values().forEach(v -> sb.append("-").append(v.toUpperCase()));
        return sb.toString();
    }
}
```

**Deliverables**:
- `VariantService.java`
- Variant generation algorithm
- SKU code generator
- Variant validation

---

## Phase 4: Search Service - Elasticsearch Setup

### 4.1 Elasticsearch Configuration (Agent Task: ES-CONFIG-001)

**Objective**: Set up Elasticsearch with proper mappings

**Index Mapping**:

```json
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 2,
    "analysis": {
      "analyzer": {
        "vietnamese_analyzer": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": [
            "lowercase",
            "asciifolding",
            "vietnamese_stop",
            "vietnamese_synonym"
          ]
        }
      },
      "filter": {
        "vietnamese_stop": {
          "type": "stop",
          "stopwords": ["của", "và", "có", "được", "trong"]
        },
        "vietnamese_synonym": {
          "type": "synonym",
          "synonyms": [
            "điện thoại, smartphone, mobile",
            "laptop, máy tính xách tay",
            "áo phông, áo thun, t-shirt"
          ]
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "productId": { "type": "keyword" },
      "sellerId": { "type": "keyword" },
      "name": {
        "type": "text",
        "analyzer": "vietnamese_analyzer",
        "fields": {
          "keyword": { "type": "keyword" },
          "suggest": {
            "type": "completion",
            "analyzer": "vietnamese_analyzer"
          }
        }
      },
      "slug": { "type": "keyword" },
      "shortDescription": {
        "type": "text",
        "analyzer": "vietnamese_analyzer"
      },
      "categoryId": { "type": "keyword" },
      "categoryName": {
        "type": "text",
        "analyzer": "vietnamese_analyzer"
      },
      "categoryPath": { "type": "keyword" },
      "brandId": { "type": "keyword" },
      "brandName": {
        "type": "text",
        "analyzer": "vietnamese_analyzer",
        "fields": {
          "keyword": { "type": "keyword" }
        }
      },
      "minPrice": { "type": "double" },
      "maxPrice": { "type": "double" },
      "status": { "type": "keyword" },
      "isFeatured": { "type": "boolean" },
      "isNewArrival": { "type": "boolean" },
      "isBestSeller": { "type": "boolean" },
      "totalSold": { "type": "integer" },
      "totalViews": { "type": "integer" },
      "avgRating": { "type": "half_float" },
      "reviewCount": { "type": "integer" },
      "primaryImage": { "type": "keyword" },
      "attributes": {
        "type": "nested",
        "properties": {
          "name": { "type": "keyword" },
          "value": { "type": "keyword" },
          "displayValue": { "type": "text" }
        }
      },
      "skus": {
        "type": "nested",
        "properties": {
          "skuId": { "type": "keyword" },
          "skuCode": { "type": "keyword" },
          "price": { "type": "double" },
          "stockQuantity": { "type": "integer" },
          "isActive": { "type": "boolean" },
          "variantAttributes": {
            "type": "object",
            "dynamic": true
          }
        }
      },
      "createdAt": { "type": "date" },
      "updatedAt": { "type": "date" },
      "publishedAt": { "type": "date" }
    }
  }
}
```

**Spring Configuration**:

```java
@Configuration
@EnableElasticsearchRepositories(basePackages = "com.example.search_service.repository")
public class ElasticsearchConfig extends ElasticsearchConfiguration {
    
    @Override
    public ClientConfiguration clientConfiguration() {
        return ClientConfiguration.builder()
            .connectedTo("localhost:9200")
            .withBasicAuth("elastic", "password")
            .withConnectTimeout(Duration.ofSeconds(5))
            .withSocketTimeout(Duration.ofSeconds(3))
            .build();
    }
}
```

**Deliverables**:
- Elasticsearch index template
- Spring Data Elasticsearch configuration
- Index management scripts
- Vietnamese analyzer configuration

---

### 4.2 Search Document Model (Agent Task: ES-MODEL-001)

**Objective**: Create Elasticsearch document class

```java
@Document(indexName = "products")
@Setting(settingPath = "elasticsearch-settings.json")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductDocument {
    
    @Id
    private String productId;
    
    @Field(type = FieldType.Keyword)
    private String sellerId;
    
    @MultiField(
        mainField = @Field(type = FieldType.Text, analyzer = "vietnamese_analyzer"),
        otherFields = {
            @InnerField(suffix = "keyword", type = FieldType.Keyword),
            @InnerField(suffix = "suggest", type = FieldType.Completion)
        }
    )
    private String name;
    
    @Field(type = FieldType.Keyword)
    private String slug;
    
    @Field(type = FieldType.Text, analyzer = "vietnamese_analyzer")
    private String shortDescription;
    
    @Field(type = FieldType.Keyword)
    private String categoryId;
    
    @Field(type = FieldType.Text, analyzer = "vietnamese_analyzer")
    private String categoryName;
    
    @Field(type = FieldType.Keyword)
    private String categoryPath;
    
    @Field(type = FieldType.Keyword)
    private String brandId;
    
    @MultiField(
        mainField = @Field(type = FieldType.Text, analyzer = "vietnamese_analyzer"),
        otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword)
    )
    private String brandName;
    
    @Field(type = FieldType.Double)
    private BigDecimal minPrice;
    
    @Field(type = FieldType.Double)
    private BigDecimal maxPrice;
    
    @Field(type = FieldType.Keyword)
    private String status;
    
    @Field(type = FieldType.Boolean)
    private boolean isFeatured;
    
    @Field(type = FieldType.Boolean)
    private boolean isNewArrival;
    
    @Field(type = FieldType.Boolean)
    private boolean isBestSeller;
    
    @Field(type = FieldType.Integer)
    private int totalSold;
    
    @Field(type = FieldType.Integer)
    private int totalViews;
    
    @Field(type = FieldType.Half_Float)
    private BigDecimal avgRating;
    
    @Field(type = FieldType.Integer)
    private int reviewCount;
    
    @Field(type = FieldType.Keyword)
    private String primaryImage;
    
    @Field(type = FieldType.Nested)
    private List<AttributeDocument> attributes;
    
    @Field(type = FieldType.Nested)
    private List<SkuDocument> skus;
    
    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant createdAt;
    
    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant updatedAt;
    
    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant publishedAt;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttributeDocument {
    private String name;
    private String value;
    private String displayValue;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkuDocument {
    private String skuId;
    private String skuCode;
    private BigDecimal price;
    private Integer stockQuantity;
    private boolean isActive;
    private Map<String, String> variantAttributes;
}
```

**Deliverables**:
- `ProductDocument.java`
- Nested document classes
- Repository interface
- Mapper from Product entity to ProductDocument

---

## Phase 5: Search Service - Search Features

### 5.1 Kafka Event Consumer (Agent Task: SEARCH-CONSUMER-001)

**Objective**: Consume product events and index to Elasticsearch

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductEventConsumer {
    private final ProductDocumentRepository documentRepository;
    private final ProductDocumentMapper documentMapper;
    
    @KafkaListener(topics = "product-events", groupId = "search-service-group")
    public void handleProductEvent(@Payload BaseEvent event) {
        log.info("Received product event: {}", event.getEventType());
        
        try {
            if (event instanceof ProductCreatedEvent) {
                handleProductCreated((ProductCreatedEvent) event);
            } else if (event instanceof ProductUpdatedEvent) {
                handleProductUpdated((ProductUpdatedEvent) event);
            } else if (event instanceof ProductStatusChangedEvent) {
                handleStatusChanged((ProductStatusChangedEvent) event);
            } else if (event instanceof ProductPriceChangedEvent) {
                handlePriceChanged((ProductPriceChangedEvent) event);
            } else if (event instanceof ProductStockUpdatedEvent) {
                handleStockUpdated((ProductStockUpdatedEvent) event);
            } else if (event instanceof ProductDeletedEvent) {
                handleProductDeleted((ProductDeletedEvent) event);
            }
        } catch (Exception e) {
            log.error("Error processing product event", e);
            // Send to DLT (Dead Letter Topic)
        }
    }
    
    private void handleProductCreated(ProductCreatedEvent event) {
        ProductDocument document = documentMapper.toDocument(event);
        documentRepository.save(document);
        log.info("Indexed new product: {}", event.getProductId());
    }
    
    private void handleProductUpdated(ProductUpdatedEvent event) {
        documentRepository.findById(event.getProductId())
            .ifPresent(doc -> {
                documentMapper.applyUpdates(doc, event.getChangedFields());
                documentRepository.save(doc);
                log.info("Updated product index: {}", event.getProductId());
            });
    }
    
    private void handleStatusChanged(ProductStatusChangedEvent event) {
        documentRepository.findById(event.getProductId())
            .ifPresent(doc -> {
                doc.setStatus(event.getNewStatus());
                documentRepository.save(doc);
            });
    }
    
    private void handlePriceChanged(ProductPriceChangedEvent event) {
        documentRepository.findById(event.getProductId())
            .ifPresent(doc -> {
                // Update SKU price
                doc.getSkus().stream()
                    .filter(sku -> sku.getSkuId().equals(event.getSkuId()))
                    .findFirst()
                    .ifPresent(sku -> sku.setPrice(event.getNewPrice()));
                
                // Recalculate min/max price
                recalculatePriceRange(doc);
                documentRepository.save(doc);
            });
    }
    
    private void handleStockUpdated(ProductStockUpdatedEvent event) {
        documentRepository.findById(event.getProductId())
            .ifPresent(doc -> {
                doc.getSkus().stream()
                    .filter(sku -> sku.getSkuId().equals(event.getSkuId()))
                    .findFirst()
                    .ifPresent(sku -> sku.setStockQuantity(event.getNewQuantity()));
                
                documentRepository.save(doc);
            });
    }
    
    private void handleProductDeleted(ProductDeletedEvent event) {
        documentRepository.deleteById(event.getProductId());
        log.info("Removed product from index: {}", event.getProductId());
    }
    
    private void recalculatePriceRange(ProductDocument doc) {
        BigDecimal min = doc.getSkus().stream()
            .filter(SkuDocument::isActive)
            .map(SkuDocument::getPrice)
            .min(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
        
        BigDecimal max = doc.getSkus().stream()
            .filter(SkuDocument::isActive)
            .map(SkuDocument::getPrice)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
        
        doc.setMinPrice(min);
        doc.setMaxPrice(max);
    }
}
```

**Deliverables**:
- Kafka event consumer
- Document mapper
- Error handling with DLT
- Retry mechanism

---

### 5.2 Search Service Implementation (Agent Task: SEARCH-SERVICE-001)

**Objective**: Implement search functionality

```java
@Service
@RequiredArgsConstructor
public class SearchService {
    private final ElasticsearchOperations elasticsearchOperations;
    private final ProductDocumentRepository documentRepository;
    
    public SearchResponse<ProductDocument> search(SearchRequest request) {
        // Build query
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        
        // Full-text search
        if (request.getQuery() != null && !request.getQuery().isEmpty()) {
            boolQuery.must(QueryBuilders.multiMatchQuery(request.getQuery())
                .field("name", 3.0f)  // Boost name
                .field("shortDescription", 1.5f)
                .field("brandName", 2.0f)
                .field("categoryName", 1.0f)
                .fuzziness(Fuzziness.AUTO)
                .prefixLength(2)
                .type(MultiMatchQueryBuilder.Type.BEST_FIELDS));
        }
        
        // Category filter
        if (request.getCategoryIds() != null && !request.getCategoryIds().isEmpty()) {
            boolQuery.filter(QueryBuilders.termsQuery("categoryId", request.getCategoryIds()));
        }
        
        // Brand filter
        if (request.getBrandIds() != null && !request.getBrandIds().isEmpty()) {
            boolQuery.filter(QueryBuilders.termsQuery("brandId", request.getBrandIds()));
        }
        
        // Price range filter
        if (request.getMinPrice() != null || request.getMaxPrice() != null) {
            RangeQueryBuilder priceRange = QueryBuilders.rangeQuery("minPrice");
            if (request.getMinPrice() != null) {
                priceRange.gte(request.getMinPrice());
            }
            if (request.getMaxPrice() != null) {
                priceRange.lte(request.getMaxPrice());
            }
            boolQuery.filter(priceRange);
        }
        
        // Rating filter
        if (request.getMinRating() != null) {
            boolQuery.filter(QueryBuilders.rangeQuery("avgRating")
                .gte(request.getMinRating()));
        }
        
        // Attribute filters (nested query)
        if (request.getAttributes() != null && !request.getAttributes().isEmpty()) {
            request.getAttributes().forEach((attrName, values) -> {
                BoolQueryBuilder nestedBool = QueryBuilders.boolQuery()
                    .must(QueryBuilders.termQuery("attributes.name", attrName))
                    .must(QueryBuilders.termsQuery("attributes.value", values));
                
                boolQuery.filter(QueryBuilders.nestedQuery(
                    "attributes",
                    nestedBool,
                    ScoreMode.None
                ));
            });
        }
        
        // Status filter (only active)
        boolQuery.filter(QueryBuilders.termQuery("status", "ACTIVE"));
        
        // Build native query
        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder()
            .withQuery(boolQuery)
            .withPageable(PageRequest.of(
                request.getPage(),
                request.getSize(),
                buildSort(request.getSortBy(), request.getSortOrder())
            ));
        
        // Add aggregations for facets
        queryBuilder.addAggregation(AggregationBuilders.terms("categories")
            .field("categoryId")
            .size(50));
        
        queryBuilder.addAggregation(AggregationBuilders.terms("brands")
            .field("brandId")
            .size(50));
        
        queryBuilder.addAggregation(AggregationBuilders.range("priceRanges")
            .field("minPrice")
            .addRange(0, 100000)
            .addRange(100000, 500000)
            .addRange(500000, 1000000)
            .addRange(1000000, 5000000)
            .addUnboundedFrom(5000000));
        
        queryBuilder.addAggregation(AggregationBuilders.terms("ratings")
            .field("avgRating"));
        
        // Execute search
        SearchHits<ProductDocument> searchHits = elasticsearchOperations.search(
            queryBuilder.build(),
            ProductDocument.class
        );
        
        // Extract aggregations
        Map<String, Aggregation> aggregations = searchHits.getAggregations().asMap();
        
        // Build response
        return SearchResponse.<ProductDocument>builder()
            .hits(searchHits.getSearchHits())
            .totalHits(searchHits.getTotalHits())
            .page(request.getPage())
            .size(request.getSize())
            .facets(buildFacets(aggregations))
            .build();
    }
    
    public List<String> autocomplete(String query) {
        SuggestBuilder suggestBuilder = new SuggestBuilder()
            .addSuggestion("product-suggest",
                SuggestBuilders.completionSuggestion("name.suggest")
                    .prefix(query)
                    .skipDuplicates(true)
                    .size(10));
        
        SearchResponse response = elasticsearchOperations.suggest(suggestBuilder, ProductDocument.class);
        
        return response.getSuggest()
            .getSuggestion("product-suggest")
            .getEntries()
            .stream()
            .flatMap(entry -> entry.getOptions().stream())
            .map(CompletionSuggestion.Entry.Option::getText)
            .map(Text::string)
            .distinct()
            .collect(Collectors.toList());
    }
    
    private Sort buildSort(String sortBy, String sortOrder) {
        Sort.Direction direction = "desc".equalsIgnoreCase(sortOrder) 
            ? Sort.Direction.DESC 
            : Sort.Direction.ASC;
        
        return switch (sortBy) {
            case "price" -> Sort.by(direction, "minPrice");
            case "sold" -> Sort.by(direction, "totalSold");
            case "rating" -> Sort.by(direction, "avgRating");
            case "newest" -> Sort.by(Sort.Direction.DESC, "createdAt");
            default -> Sort.by(Sort.Direction.DESC, "_score");
        };
    }
    
    private Map<String, FacetDto> buildFacets(Map<String, Aggregation> aggregations) {
        Map<String, FacetDto> facets = new HashMap<>();
        
        // Process category facet
        Terms categoryTerms = (Terms) aggregations.get("categories");
        facets.put("categories", FacetDto.builder()
            .name("categories")
            .values(categoryTerms.getBuckets().stream()
                .map(bucket -> FacetValueDto.builder()
                    .value(bucket.getKeyAsString())
                    .count(bucket.getDocCount())
                    .build())
                .collect(Collectors.toList()))
            .build());
        
        // Process brand facet
        Terms brandTerms = (Terms) aggregations.get("brands");
        facets.put("brands", FacetDto.builder()
            .name("brands")
            .values(brandTerms.getBuckets().stream()
                .map(bucket -> FacetValueDto.builder()
                    .value(bucket.getKeyAsString())
                    .count(bucket.getDocCount())
                    .build())
                .collect(Collectors.toList()))
            .build());
        
        // Process price range facet
        Range priceRanges = (Range) aggregations.get("priceRanges");
        facets.put("priceRanges", FacetDto.builder()
            .name("priceRanges")
            .values(priceRanges.getBuckets().stream()
                .map(bucket -> FacetValueDto.builder()
                    .value(bucket.getKeyAsString())
                    .count(bucket.getDocCount())
                    .build())
                .collect(Collectors.toList()))
            .build());
        
        return facets;
    }
}
```

**Deliverables**:
- `SearchService.java`
- Query builder with filters
- Faceted search support
- Autocomplete/suggest feature
- Sorting implementation

---

### 5.3 Search API (Agent Task: SEARCH-API-001)

**Objective**: REST endpoints for search

```java
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {
    private final SearchService searchService;
    
    @GetMapping("/products")
    public ResponseEntity<SearchResponse<ProductDocument>> searchProducts(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) List<String> categories,
        @RequestParam(required = false) List<String> brands,
        @RequestParam(required = false) BigDecimal minPrice,
        @RequestParam(required = false) BigDecimal maxPrice,
        @RequestParam(required = false) BigDecimal minRating,
        @RequestParam(required = false) Map<String, List<String>> attributes,
        @RequestParam(defaultValue = "relevance") String sortBy,
        @RequestParam(defaultValue = "desc") String sortOrder,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        SearchRequest request = SearchRequest.builder()
            .query(q)
            .categoryIds(categories)
            .brandIds(brands)
            .minPrice(minPrice)
            .maxPrice(maxPrice)
            .minRating(minRating)
            .attributes(attributes)
            .sortBy(sortBy)
            .sortOrder(sortOrder)
            .page(page)
            .size(size)
            .build();
        
        SearchResponse<ProductDocument> response = searchService.search(request);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/autocomplete")
    public ResponseEntity<List<String>> autocomplete(
        @RequestParam String q
    ) {
        List<String> suggestions = searchService.autocomplete(q);
        return ResponseEntity.ok(suggestions);
    }
}
```

**Deliverables**:
- `SearchController.java`
- Search request/response DTOs
- Query parameter binding
- API documentation

---

## Phase 6: Integration & Optimization

### 6.1 Cache Strategy (Agent Task: CACHE-PRODUCT-001)

**Redis Caching**:

```java
@Service
@RequiredArgsConstructor
public class ProductCacheService {
    private final RedisService redisService;
    
    public void cacheProduct(ProductDto product) {
        redisService.set(
            "product:" + product.getProductId(),
            product,
            Duration.ofHours(1)
        );
    }
    
    public void cacheCategoryTree(List<CategoryDto> tree) {
        redisService.set(
            "category:tree",
            tree,
            Duration.ofHours(24)
        );
    }
    
    public void cacheFeaturedProducts(List<ProductDto> products) {
        redisService.set(
            "products:featured",
            products,
            Duration.ofMinutes(15)
        );
    }
    
    public void invalidateProductCache(UUID productId) {
        redisService.delete("product:" + productId);
        // Also invalidate seller products cache
        String sellerCachePattern = "seller:*:products";
        redisService.deletePattern(sellerCachePattern);
    }
}
```

---

### 6.2 Performance Optimization (Agent Task: PERF-PRODUCT-001)

**Optimizations**:
1. Database query optimization (add missing indexes)
2. N+1 query prevention (use @EntityGraph)
3. Elasticsearch query caching
4. Redis connection pooling
5. Async event publishing

**Example - N+1 Prevention**:

```java
public interface ProductRepository extends JpaRepository<Product, UUID> {
    
    @EntityGraph(attributePaths = {"skus", "media", "category", "brand"})
    Optional<Product> findByIdWithDetails(UUID productId);
    
    @Query("SELECT p FROM Product p " +
           "LEFT JOIN FETCH p.skus " +
           "WHERE p.sellerId = :sellerId AND p.status IN :statuses")
    List<Product> findBySellerIdWithSkus(
        @Param("sellerId") UUID sellerId,
        @Param("statuses") List<ProductStatus> statuses
    );
}
```

---

## AI Agent Task Breakdown

### Week 1: Product Service Foundation
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| DB-PRODUCT-001 | Database schema | 6h | - |
| PRODUCT-DOMAIN-001 | Domain model | 8h | DB-PRODUCT-001 |
| PRODUCT-EVENT-001 | Domain events | 4h | PRODUCT-DOMAIN-001 |
| PRODUCT-AGGREGATE-001 | Aggregate implementation | 10h | PRODUCT-EVENT-001 |

### Week 2: Product Service Core
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| PRODUCT-SERVICE-001 | Service layer | 12h | PRODUCT-AGGREGATE-001 |
| PRODUCT-API-001 | REST API | 8h | PRODUCT-SERVICE-001 |
| PRODUCT-BULK-001 | Bulk operations | 6h | PRODUCT-SERVICE-001 |
| PRODUCT-VARIANT-001 | Variant management | 6h | PRODUCT-SERVICE-001 |

### Week 3: Search Service
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| ES-CONFIG-001 | Elasticsearch setup | 4h | - |
| ES-MODEL-001 | Document model | 4h | ES-CONFIG-001 |
| SEARCH-CONSUMER-001 | Kafka consumer | 6h | ES-MODEL-001 |
| SEARCH-SERVICE-001 | Search service | 10h | SEARCH-CONSUMER-001 |
| SEARCH-API-001 | Search API | 6h | SEARCH-SERVICE-001 |

### Week 4: Optimization & Testing
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| CACHE-PRODUCT-001 | Caching strategy | 4h | All services |
| PERF-PRODUCT-001 | Performance tuning | 6h | All services |
| TEST-PRODUCT-001 | Unit tests | 12h | All services |
| TEST-PRODUCT-INT-001 | Integration tests | 10h | All services |

---

## Validation Checklist

### Product Service
- [ ] Products can be created with SKUs
- [ ] Price updates trigger events
- [ ] Stock updates work correctly
- [ ] Status transitions validated
- [ ] Bulk operations functional
- [ ] Variant generation works
- [ ] Events published to Kafka
- [ ] Cache invalidation working

### Search Service
- [ ] Elasticsearch index created
- [ ] Products indexed from Kafka events
- [ ] Full-text search works
- [ ] Filters apply correctly
- [ ] Faceted search returns aggregations
- [ ] Autocomplete suggestions work
- [ ] Sorting functional
- [ ] Search performance < 100ms

### Integration
- [ ] Product events consumed by Search Service
- [ ] Index updated on product changes
- [ ] Cache hit rate > 85%
- [ ] No data inconsistency between services

### Performance
- [ ] Product creation < 500ms
- [ ] Search query < 100ms
- [ ] Bulk import handles 1000+ products
- [ ] Concurrent updates work correctly
- [ ] No memory leaks

---

**End of Catalog Services Implementation Plan**
