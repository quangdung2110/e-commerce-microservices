# Group 2: Product Catalog & Discovery Services

> **Services**: Product Service, Search Service
> **Pattern**: Event Sourcing + CQRS + Saga (Temporal)
> **Database**: PostgreSQL (Product) | Elasticsearch (Search)

---

## Table of Contents
1. [Product Service](#1-product-service)
2. [Search Service](#2-search-service)
3. [Cross-Service Integration](#3-cross-service-integration)
4. [Kafka Topics & Redis Caching](#4-kafka-topics--redis-caching)

---

## 1. Product Service

### 1.1 Issues Found in User's Design

| # | Issue | Severity | Fix |
|---|-------|----------|-----|
| 1 | Missing `version` for optimistic locking | HIGH | Add `version` to products and product_skus |
| 2 | Attributes structure too simple | MEDIUM | Add `category_attributes` junction table for category-specific attributes |
| 3 | Missing attribute values master table | MEDIUM | Add `attribute_values` for predefined options |
| 4 | `OUT_OF_STOCK` is wrong status concept | HIGH | Status should be DRAFT/ACTIVE/INACTIVE/DISCONTINUED, stock is separate |
| 5 | Missing `brand` entity | MEDIUM | Add `brands` table for brand management |
| 6 | Missing `min_price`, `max_price` denormalized | MEDIUM | Add for fast filtering without joining SKUs |
| 7 | Missing `total_sold`, `total_views` | MEDIUM | Add for popularity sorting |
| 8 | Missing `deleted_at` soft delete | MEDIUM | Add for audit trail |
| 9 | `media_url` redundant with storage_key | LOW | Remove, compute URL from key |
| 10 | Missing `display_order` in media | LOW | Add for consistent ordering |
| 11 | Missing `is_active` in SKUs | MEDIUM | Individual SKU can be disabled |
| 12 | Missing SEO fields | MEDIUM | Add `seo_title`, `seo_description`, `slug` |

### 1.2 Database Schema

#### Table: `categories`
```sql
CREATE TABLE categories (
    category_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id           UUID REFERENCES categories(category_id),
    
    -- Basic info
    name                VARCHAR(200) NOT NULL,
    slug                VARCHAR(200) NOT NULL UNIQUE,
    description         TEXT,
    icon_url            TEXT,
    image_url           TEXT,
    
    -- Hierarchy
    level               SMALLINT NOT NULL DEFAULT 0,
    path                TEXT NOT NULL,  -- Materialized path: "1/5/23"
    
    -- Display
    display_order       INTEGER NOT NULL DEFAULT 0,
    is_active           BOOLEAN NOT NULL DEFAULT true,
    is_featured         BOOLEAN NOT NULL DEFAULT false,
    
    -- SEO
    seo_title           VARCHAR(200),
    seo_description     TEXT,
    
    -- Metadata
    product_count       INTEGER NOT NULL DEFAULT 0,  -- Denormalized
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             INTEGER NOT NULL DEFAULT 1,
    
    CONSTRAINT chk_no_self_parent CHECK (parent_id != category_id)
);

CREATE INDEX idx_categories_parent ON categories(parent_id);
CREATE INDEX idx_categories_path ON categories USING GIST (path gist_trgm_ops);
CREATE INDEX idx_categories_slug ON categories(slug);
CREATE INDEX idx_categories_active ON categories(is_active, display_order);
```

#### Table: `brands`
```sql
CREATE TABLE brands (
    brand_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Basic info
    name                VARCHAR(200) NOT NULL,
    slug                VARCHAR(200) NOT NULL UNIQUE,
    description         TEXT,
    logo_url            TEXT,
    website_url         TEXT,
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    is_official         BOOLEAN NOT NULL DEFAULT false,  -- Official brand store
    
    -- Verification
    verified_at         TIMESTAMPTZ,
    verified_by         UUID,
    
    -- Stats
    product_count       INTEGER NOT NULL DEFAULT 0,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_brands_slug ON brands(slug);
CREATE INDEX idx_brands_active ON brands(is_active);
```

#### Table: `attributes`
```sql
CREATE TABLE attributes (
    attribute_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Basic info
    name                VARCHAR(100) NOT NULL,
    code                VARCHAR(50) NOT NULL UNIQUE,  -- e.g., "color", "size"
    description         TEXT,
    
    -- Type
    data_type           VARCHAR(20) NOT NULL CHECK (
        data_type IN ('STRING', 'NUMBER', 'BOOLEAN', 'DATE', 'SELECT', 'MULTI_SELECT')
    ),
    
    -- Validation
    is_required         BOOLEAN NOT NULL DEFAULT false,
    is_filterable       BOOLEAN NOT NULL DEFAULT true,
    is_searchable       BOOLEAN NOT NULL DEFAULT true,
    is_variant          BOOLEAN NOT NULL DEFAULT false,  -- Used for SKU variants
    
    -- Display
    display_order       INTEGER NOT NULL DEFAULT 0,
    unit                VARCHAR(20),  -- e.g., "cm", "kg"
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_attributes_code ON attributes(code);
CREATE INDEX idx_attributes_variant ON attributes(is_variant) WHERE is_variant = true;
```

#### Table: `attribute_values`
```sql
CREATE TABLE attribute_values (
    value_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attribute_id        UUID NOT NULL REFERENCES attributes(attribute_id),
    
    -- Value
    value               VARCHAR(200) NOT NULL,
    display_value       VARCHAR(200),  -- For display (e.g., "Đỏ" instead of "red")
    
    -- Visual (for color, etc.)
    color_hex           VARCHAR(7),
    image_url           TEXT,
    
    -- Order
    display_order       INTEGER NOT NULL DEFAULT 0,
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    CONSTRAINT uq_attribute_value UNIQUE (attribute_id, value)
);

CREATE INDEX idx_attr_values_attribute ON attribute_values(attribute_id);
```

#### Table: `category_attributes`
```sql
-- Which attributes apply to which categories
CREATE TABLE category_attributes (
    category_id         UUID NOT NULL REFERENCES categories(category_id),
    attribute_id        UUID NOT NULL REFERENCES attributes(attribute_id),
    
    -- Category-specific settings
    is_required         BOOLEAN NOT NULL DEFAULT false,
    display_order       INTEGER NOT NULL DEFAULT 0,
    
    PRIMARY KEY (category_id, attribute_id)
);

CREATE INDEX idx_cat_attrs_category ON category_attributes(category_id);
```

#### Table: `products`
```sql
CREATE TABLE products (
    product_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Ownership
    seller_id           UUID NOT NULL,  -- FK to sellers.seller_id
    
    -- Classification
    category_id         UUID NOT NULL REFERENCES categories(category_id),
    brand_id            UUID REFERENCES brands(brand_id),
    
    -- Basic info
    name                VARCHAR(500) NOT NULL,
    slug                VARCHAR(500) NOT NULL,
    short_description   VARCHAR(1000),
    description         TEXT,
    
    -- Pricing (denormalized from SKUs)
    min_price           DECIMAL(15, 2) NOT NULL,
    max_price           DECIMAL(15, 2) NOT NULL,
    original_min_price  DECIMAL(15, 2),  -- Before discount
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (
        status IN ('DRAFT', 'PENDING_REVIEW', 'ACTIVE', 'INACTIVE', 'DISCONTINUED', 'REJECTED')
    ),
    rejection_reason    TEXT,
    
    -- Flags
    is_featured         BOOLEAN NOT NULL DEFAULT false,
    is_new_arrival      BOOLEAN NOT NULL DEFAULT false,
    is_best_seller      BOOLEAN NOT NULL DEFAULT false,
    
    -- Stats (denormalized)
    total_sold          INTEGER NOT NULL DEFAULT 0,
    total_views         INTEGER NOT NULL DEFAULT 0,
    total_favorites     INTEGER NOT NULL DEFAULT 0,
    avg_rating          DECIMAL(3, 2),
    review_count        INTEGER NOT NULL DEFAULT 0,
    
    -- SEO
    seo_title           VARCHAR(200),
    seo_description     TEXT,
    seo_keywords        TEXT[],
    
    -- Shipping
    weight_kg           DECIMAL(10, 3),
    length_cm           DECIMAL(10, 2),
    width_cm            DECIMAL(10, 2),
    height_cm           DECIMAL(10, 2),
    
    -- Soft delete
    deleted_at          TIMESTAMPTZ,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at        TIMESTAMPTZ,
    version             INTEGER NOT NULL DEFAULT 1,
    
    CONSTRAINT uq_product_slug_seller UNIQUE (seller_id, slug)
);

CREATE INDEX idx_products_seller ON products(seller_id, status);
CREATE INDEX idx_products_category ON products(category_id, status);
CREATE INDEX idx_products_brand ON products(brand_id) WHERE brand_id IS NOT NULL;
CREATE INDEX idx_products_status ON products(status, created_at DESC);
CREATE INDEX idx_products_price ON products(min_price, max_price) WHERE status = 'ACTIVE';
CREATE INDEX idx_products_search ON products USING gin(to_tsvector('vietnamese', name || ' ' || COALESCE(short_description, '')));
CREATE INDEX idx_products_featured ON products(is_featured, total_sold DESC) WHERE status = 'ACTIVE';
CREATE INDEX idx_products_deleted ON products(deleted_at) WHERE deleted_at IS NOT NULL;
```

#### Table: `product_skus`
```sql
CREATE TABLE product_skus (
    sku_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id          UUID NOT NULL REFERENCES products(product_id) ON DELETE CASCADE,
    
    -- SKU identification
    sku_code            VARCHAR(100) NOT NULL,
    barcode             VARCHAR(100),
    
    -- Variant attributes (JSON for flexibility)
    variant_attributes  JSONB NOT NULL DEFAULT '{}',
    /*
    Example: {
        "color": {"value": "red", "display": "Đỏ"},
        "size": {"value": "L", "display": "L"}
    }
    */
    
    -- Pricing
    price               DECIMAL(15, 2) NOT NULL,
    original_price      DECIMAL(15, 2),  -- Before discount
    cost_price          DECIMAL(15, 2),  -- For profit calculation
    
    -- Inventory (denormalized for display)
    stock_quantity      INTEGER NOT NULL DEFAULT 0,
    reserved_quantity   INTEGER NOT NULL DEFAULT 0,
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    is_default          BOOLEAN NOT NULL DEFAULT false,
    
    -- Images (specific to this variant)
    image_urls          TEXT[],
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             INTEGER NOT NULL DEFAULT 1,
    
    CONSTRAINT uq_sku_code_seller UNIQUE (product_id, sku_code)
);

CREATE INDEX idx_skus_product ON product_skus(product_id);
CREATE INDEX idx_skus_code ON product_skus(sku_code);
CREATE INDEX idx_skus_barcode ON product_skus(barcode) WHERE barcode IS NOT NULL;
CREATE INDEX idx_skus_active ON product_skus(product_id, is_active) WHERE is_active = true;
CREATE INDEX idx_skus_variant ON product_skus USING gin(variant_attributes);
```

#### Table: `product_attributes`
```sql
-- Custom attributes for each product
CREATE TABLE product_attributes (
    product_id          UUID NOT NULL REFERENCES products(product_id) ON DELETE CASCADE,
    attribute_id        UUID NOT NULL REFERENCES attributes(attribute_id),
    
    -- Value (flexible)
    value_text          TEXT,
    value_number        DECIMAL(15, 4),
    value_boolean       BOOLEAN,
    value_date          DATE,
    value_ids           UUID[],  -- For SELECT/MULTI_SELECT (references attribute_values)
    
    PRIMARY KEY (product_id, attribute_id)
);

CREATE INDEX idx_prod_attrs_product ON product_attributes(product_id);
```

#### Table: `product_media`
```sql
CREATE TABLE product_media (
    media_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id          UUID NOT NULL REFERENCES products(product_id) ON DELETE CASCADE,
    sku_id              UUID REFERENCES product_skus(sku_id),  -- NULL = product level
    
    -- Media type
    media_type          VARCHAR(10) NOT NULL CHECK (media_type IN ('IMAGE', 'VIDEO')),
    
    -- Storage
    storage_key         VARCHAR(500) NOT NULL,  -- S3 key
    thumbnail_key       VARCHAR(500),
    
    -- Metadata
    width               INTEGER,
    height              INTEGER,
    file_size_bytes     INTEGER,
    duration_seconds    INTEGER,  -- For video
    alt_text            VARCHAR(500),
    
    -- Display
    display_order       INTEGER NOT NULL DEFAULT 0,
    is_primary          BOOLEAN NOT NULL DEFAULT false,
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (
        status IN ('PROCESSING', 'ACTIVE', 'FAILED', 'DELETED')
    ),
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_media_product ON product_media(product_id, display_order);
CREATE INDEX idx_media_sku ON product_media(sku_id) WHERE sku_id IS NOT NULL;
CREATE INDEX idx_media_primary ON product_media(product_id, is_primary) WHERE is_primary = true;
```

### 1.3 Event Sourcing

#### Domain Events
```java
@SuperBuilder
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class ProductEvent extends BaseEvent {
    protected String productId;
    protected String sellerId;
}

@DomainEvent(aggregateType = "Product")
public class ProductCreatedEvent extends ProductEvent {
    private String name;
    private String categoryId;
    private String brandId;
    private String description;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private List<ProductSkuDto> skus;
    private List<ProductMediaDto> media;
}

@DomainEvent(aggregateType = "Product")
public class ProductUpdatedEvent extends ProductEvent {
    private Map<String, Object> changedFields;
    private Integer previousVersion;
}

@DomainEvent(aggregateType = "Product")
public class ProductStatusChangedEvent extends ProductEvent {
    private String previousStatus;
    private String newStatus;
    private String reason;
    private String changedBy;
}

@DomainEvent(aggregateType = "Product")
public class ProductPriceChangedEvent extends ProductEvent {
    private String skuId;
    private BigDecimal previousPrice;
    private BigDecimal newPrice;
    private String reason;  // MANUAL, PROMOTION, FLASH_SALE
}

@DomainEvent(aggregateType = "Product")
public class ProductStockUpdatedEvent extends ProductEvent {
    private String skuId;
    private Integer previousQuantity;
    private Integer newQuantity;
    private String reason;  // SALE, RESTOCK, ADJUSTMENT, RETURN
}

@DomainEvent(aggregateType = "Product")
public class ProductViewedEvent extends ProductEvent {
    private String userId;
    private String sessionId;
    private String source;  // SEARCH, RECOMMENDATION, DIRECT
}

@DomainEvent(aggregateType = "Product")
public class ProductDeletedEvent extends ProductEvent {
    private String deletedBy;
    private String reason;
}
```

#### Aggregate
```java
@Aggregate
public class ProductAggregate {
    private String productId;
    private String sellerId;
    private String name;
    private String status;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private Map<String, ProductSku> skus = new HashMap<>();
    private int version;
    private List<ProductEvent> uncommittedEvents = new ArrayList<>();
    
    public void create(CreateProductCommand cmd) {
        // Validate
        if (cmd.getSkus() == null || cmd.getSkus().isEmpty()) {
            throw new IllegalArgumentException("Product must have at least one SKU");
        }
        
        // Calculate prices
        BigDecimal minPrice = cmd.getSkus().stream()
            .map(ProductSkuDto::getPrice)
            .min(BigDecimal::compareTo)
            .orElseThrow();
        BigDecimal maxPrice = cmd.getSkus().stream()
            .map(ProductSkuDto::getPrice)
            .max(BigDecimal::compareTo)
            .orElseThrow();
        
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
            .build());
    }
    
    public void updatePrice(String skuId, BigDecimal newPrice, String reason) {
        ProductSku sku = skus.get(skuId);
        if (sku == null) {
            throw new IllegalArgumentException("SKU not found: " + skuId);
        }
        
        apply(ProductPriceChangedEvent.builder()
            .aggregateId(productId)
            .productId(productId)
            .sellerId(sellerId)
            .skuId(skuId)
            .previousPrice(sku.getPrice())
            .newPrice(newPrice)
            .reason(reason)
            .build());
        
        // Recalculate product min/max price
        recalculatePriceRange();
    }
    
    public void changeStatus(String newStatus, String reason, String changedBy) {
        if (!isValidStatusTransition(this.status, newStatus)) {
            throw new IllegalStateException(
                "Invalid status transition: " + this.status + " → " + newStatus);
        }
        
        apply(ProductStatusChangedEvent.builder()
            .aggregateId(productId)
            .productId(productId)
            .sellerId(sellerId)
            .previousStatus(this.status)
            .newStatus(newStatus)
            .reason(reason)
            .changedBy(changedBy)
            .build());
    }
    
    private boolean isValidStatusTransition(String from, String to) {
        Map<String, Set<String>> validTransitions = Map.of(
            "DRAFT", Set.of("PENDING_REVIEW", "ACTIVE"),
            "PENDING_REVIEW", Set.of("ACTIVE", "REJECTED", "DRAFT"),
            "ACTIVE", Set.of("INACTIVE", "DISCONTINUED"),
            "INACTIVE", Set.of("ACTIVE", "DISCONTINUED"),
            "REJECTED", Set.of("DRAFT"),
            "DISCONTINUED", Set.of()  // Terminal state
        );
        return validTransitions.getOrDefault(from, Set.of()).contains(to);
    }
}
```

### 1.4 Workflow: Product Lifecycle

```
┌───────────────────────────────────────────────────────────────────────┐
│                      Product State Machine                            │
└───────────────────────────────────────────────────────────────────────┘

                    ┌──────────┐
                    │  DRAFT   │◀─────────────────────────────┐
                    └────┬─────┘                              │
                         │                                    │
                         │ Submit for Review                  │ Revise
                         ▼                                    │
                ┌────────────────┐                            │
                │ PENDING_REVIEW │────────────────────────────┤
                └───────┬────────┘                            │
                        │                                     │
           ┌────────────┼────────────┐                        │
           │            │            │                        │
           ▼            ▼            ▼                        │
      ┌────────┐   ┌────────┐   ┌──────────┐                  │
      │ ACTIVE │   │REJECTED│───┘          │                  │
      └───┬────┘   └────────┘              │                  │
          │                                │                  │
          │ Deactivate                     │                  │
          ▼                                │                  │
     ┌──────────┐                          │                  │
     │ INACTIVE │──────────────────────────┤                  │
     └────┬─────┘    Reactivate            │                  │
          │                                │                  │
          │ Discontinue                    │                  │
          ▼                                │                  │
    ┌──────────────┐                       │                  │
    │ DISCONTINUED │ (Terminal State)      │                  │
    └──────────────┘                       │                  │
```

---

## 2. Search Service

### 2.1 Overview

Search Service uses **Elasticsearch** for full-text search with Vietnamese language support, faceted filtering, and personalized ranking.

### 2.2 Elasticsearch Index Design

#### Index: `products`
```json
{
  "settings": {
    "number_of_shards": 5,
    "number_of_replicas": 1,
    "analysis": {
      "analyzer": {
        "vietnamese_analyzer": {
          "type": "custom",
          "tokenizer": "icu_tokenizer",
          "filter": [
            "icu_folding",
            "lowercase",
            "vietnamese_stop",
            "edge_ngram_filter"
          ]
        },
        "autocomplete_analyzer": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": [
            "lowercase",
            "edge_ngram_filter"
          ]
        }
      },
      "filter": {
        "vietnamese_stop": {
          "type": "stop",
          "stopwords": ["và", "của", "là", "có", "được", "cho", "với", "này", "đó", "các"]
        },
        "edge_ngram_filter": {
          "type": "edge_ngram",
          "min_gram": 2,
          "max_gram": 20
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "product_id": { "type": "keyword" },
      "seller_id": { "type": "keyword" },
      "category_id": { "type": "keyword" },
      "category_path": { "type": "keyword" },
      "brand_id": { "type": "keyword" },
      
      "name": {
        "type": "text",
        "analyzer": "vietnamese_analyzer",
        "fields": {
          "keyword": { "type": "keyword" },
          "autocomplete": {
            "type": "text",
            "analyzer": "autocomplete_analyzer",
            "search_analyzer": "standard"
          }
        }
      },
      
      "description": {
        "type": "text",
        "analyzer": "vietnamese_analyzer"
      },
      
      "min_price": { "type": "scaled_float", "scaling_factor": 100 },
      "max_price": { "type": "scaled_float", "scaling_factor": 100 },
      
      "status": { "type": "keyword" },
      
      "attributes": {
        "type": "nested",
        "properties": {
          "attribute_code": { "type": "keyword" },
          "value": { "type": "keyword" },
          "value_number": { "type": "float" }
        }
      },
      
      "skus": {
        "type": "nested",
        "properties": {
          "sku_id": { "type": "keyword" },
          "sku_code": { "type": "keyword" },
          "price": { "type": "scaled_float", "scaling_factor": 100 },
          "stock_quantity": { "type": "integer" },
          "variant_attributes": { "type": "flattened" }
        }
      },
      
      "primary_image_url": { "type": "keyword", "index": false },
      
      "total_sold": { "type": "integer" },
      "total_views": { "type": "integer" },
      "total_favorites": { "type": "integer" },
      "avg_rating": { "type": "half_float" },
      "review_count": { "type": "integer" },
      
      "seller_rating": { "type": "half_float" },
      "seller_response_rate": { "type": "half_float" },
      
      "is_featured": { "type": "boolean" },
      "is_new_arrival": { "type": "boolean" },
      "is_best_seller": { "type": "boolean" },
      "has_discount": { "type": "boolean" },
      
      "created_at": { "type": "date" },
      "updated_at": { "type": "date" },
      
      "location": { "type": "geo_point" },
      
      "suggest": {
        "type": "completion",
        "analyzer": "vietnamese_analyzer",
        "contexts": [
          { "name": "category", "type": "category" }
        ]
      }
    }
  }
}
```

#### Index: `search_suggestions`
```json
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 1
  },
  "mappings": {
    "properties": {
      "query": {
        "type": "text",
        "analyzer": "autocomplete_analyzer",
        "search_analyzer": "standard",
        "fields": {
          "keyword": { "type": "keyword" }
        }
      },
      "category_id": { "type": "keyword" },
      "search_count": { "type": "integer" },
      "conversion_rate": { "type": "float" },
      "last_searched_at": { "type": "date" }
    }
  }
}
```

### 2.3 PostgreSQL Tables (for persistence)

#### Table: `search_queries`
```sql
-- Track all search queries for analytics and suggestions
CREATE TABLE search_queries (
    query_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Query
    query_text          TEXT NOT NULL,
    query_normalized    TEXT NOT NULL,
    
    -- User context
    user_id             UUID,
    session_id          VARCHAR(100) NOT NULL,
    
    -- Results
    total_results       INTEGER NOT NULL,
    results_shown       INTEGER NOT NULL,
    
    -- Filters
    filters_applied     JSONB,
    
    -- Engagement
    clicked_positions   INTEGER[],
    clicked_product_ids UUID[],
    
    -- Outcome
    led_to_cart         BOOLEAN NOT NULL DEFAULT false,
    led_to_purchase     BOOLEAN NOT NULL DEFAULT false,
    
    -- Timestamps
    searched_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_search_queries_text ON search_queries USING gin(to_tsvector('simple', query_normalized));
CREATE INDEX idx_search_queries_user ON search_queries(user_id, searched_at DESC) WHERE user_id IS NOT NULL;
CREATE INDEX idx_search_queries_time ON search_queries(searched_at DESC);
```

#### Table: `search_synonyms`
```sql
-- Custom synonyms for search improvement
CREATE TABLE search_synonyms (
    synonym_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Synonym group
    term                VARCHAR(200) NOT NULL,
    synonyms            TEXT[] NOT NULL,
    
    -- Type
    synonym_type        VARCHAR(20) NOT NULL CHECK (
        synonym_type IN ('EQUIVALENT', 'ONE_WAY', 'EXPANSION')
    ),
    
    -- Scope
    category_id         UUID,  -- NULL = all categories
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID
);

CREATE UNIQUE INDEX idx_synonyms_term ON search_synonyms(term, category_id);
```

#### Table: `search_boosts`
```sql
-- Boosting rules for search ranking
CREATE TABLE search_boosts (
    boost_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Target
    boost_type          VARCHAR(30) NOT NULL CHECK (
        boost_type IN ('PRODUCT', 'SELLER', 'CATEGORY', 'BRAND', 'KEYWORD')
    ),
    target_id           UUID,
    keyword             VARCHAR(200),
    
    -- Boost value
    boost_value         DECIMAL(5, 2) NOT NULL DEFAULT 1.0,
    
    -- Validity
    valid_from          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_until         TIMESTAMPTZ,
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID
);

CREATE INDEX idx_boosts_active ON search_boosts(boost_type, is_active, valid_from, valid_until);
```

### 2.4 Workflow: Search Query Processing

```
┌───────────────────────────────────────────────────────────────────────┐
│                      Search Query Pipeline                            │
└───────────────────────────────────────────────────────────────────────┘

User Query                     Search Service              Elasticsearch
    │                               │                           │
    │─── "áo thun nam" ────────────▶│                           │
    │                               │                           │
    │                               │─── 1. Normalize Query ────┤
    │                               │    - Lowercase            │
    │                               │    - Remove accents       │
    │                               │    - Spell check          │
    │                               │                           │
    │                               │─── 2. Expand Query ───────┤
    │                               │    - Synonyms: t-shirt    │
    │                               │    - Related: áo polo     │
    │                               │                           │
    │                               │─── 3. Build ES Query ────▶│
    │                               │                           │
    │                               │    {                      │
    │                               │      "bool": {            │
    │                               │        "must": [          │
    │                               │          {"multi_match":  │
    │                               │           {"query":"áo thun nam"}}
    │                               │        ],                 │
    │                               │        "filter": [        │
    │                               │          {"term": {"status": "ACTIVE"}}
    │                               │        ]                  │
    │                               │      }                    │
    │                               │    }                      │
    │                               │                           │
    │                               │◀── 4. Search Results ─────│
    │                               │                           │
    │                               │─── 5. Apply Boosts ───────┤
    │                               │    - Featured products    │
    │                               │    - Seller reputation    │
    │                               │    - Personalization      │
    │                               │                           │
    │                               │─── 6. Build Facets ───────┤
    │                               │    - Categories           │
    │                               │    - Brands               │
    │                               │    - Price ranges         │
    │                               │    - Attributes           │
    │                               │                           │
    │◀── Search Response ───────────│                           │
    │    {products, facets,         │                           │
    │     suggestions}              │                           │
```

---

## 3. Cross-Service Integration

### 3.1 Event Flow

```
Product Service ───────────────────────────────────────────────────────────┐
    │                                                                      │
    │─── ProductCreatedEvent ─────────────────────────────────────────────▶│
    │─── ProductUpdatedEvent ─────────────────────────────────────────────▶│
    │─── ProductStatusChangedEvent ───────────────────────────────────────▶│
    │─── ProductPriceChangedEvent ────────────────────────────────────────▶│
    │                                                             Search Service
    │                                                             (Index update)
    └──────────────────────────────────────────────────────────────────────┘

Inventory Service ─────────────────────────────────────────────────────────┐
    │                                                                      │
    │─── InventoryUpdatedEvent ───────────────────────────────────────────▶│
    │    (Update stock_quantity in ES)                        Search Service
    │                                                                      │
    └──────────────────────────────────────────────────────────────────────┘

Review Service ────────────────────────────────────────────────────────────┐
    │                                                                      │
    │─── ReviewStatsUpdatedEvent ─────────────────────────────────────────▶│
    │    (Update avg_rating, review_count)                    Search Service
    │                                                                      │
    └──────────────────────────────────────────────────────────────────────┘

Order Service ─────────────────────────────────────────────────────────────┐
    │                                                                      │
    │─── OrderCompletedEvent ─────────────────────────────────────────────▶│
    │    (Update total_sold)                                  Search Service
    │                                                                      │
    └──────────────────────────────────────────────────────────────────────┘
```

---

## 4. Kafka Topics & Redis Caching

### 4.1 Kafka Topics

| Topic | Producer | Consumer | Payload |
|-------|----------|----------|---------|
| `product-events` | Product Service | Search, Analytics, Inventory | ProductEvent |
| `search-events` | Search Service | Analytics | SearchQueryEvent |
| `product-index-commands` | Product Service | Search Service | IndexProductCommand |

### 4.2 Redis Caching

| Key Pattern | TTL | Purpose |
|-------------|-----|---------|
| `product:{product_id}` | 5 min | Product detail cache |
| `product:{product_id}:skus` | 5 min | SKUs list cache |
| `category:{category_id}:tree` | 30 min | Category tree |
| `search:suggestions:{prefix}` | 1 hour | Autocomplete suggestions |
| `search:popular:{category}` | 15 min | Popular searches |
| `search:facets:{query_hash}` | 5 min | Faceted filters cache |

### 4.3 Redis Data Structures

```
# Product cache (String with JSON)
SET product:abc-123 '{"id":"abc-123","name":"Áo thun nam","price":250000,...}'
EXPIRE product:abc-123 300

# Category tree (Hash)
HSET category:tree:root
    "cat-1" '{"name":"Thời trang","children":["cat-1a","cat-1b"]}'
    "cat-2" '{"name":"Điện tử","children":["cat-2a","cat-2b"]}'
EXPIRE category:tree:root 1800

# Autocomplete suggestions (Sorted Set)
ZADD search:suggestions:ao 100 "áo thun nam" 80 "áo khoác" 60 "áo sơ mi"
EXPIRE search:suggestions:ao 3600

# Popular searches per category (Sorted Set)
ZADD search:popular:cat-fashion 1000 "áo thun" 800 "quần jean" 600 "váy đầm"
EXPIRE search:popular:cat-fashion 900

# Search facets cache (String with JSON)
SET search:facets:hash123 '{"categories":[...],"brands":[...],"price_ranges":[...]}'
EXPIRE search:facets:hash123 300
```

---

## Summary

### Tables Created

| Service | Tables | Purpose |
|---------|--------|---------|
| **Product** | categories, brands, attributes, attribute_values, category_attributes, products, product_skus, product_attributes, product_media | Complete product catalog |
| **Search** | search_queries, search_synonyms, search_boosts | Search analytics and customization |

### Elasticsearch Indices

| Index | Purpose |
|-------|---------|
| `products` | Main product search with Vietnamese analyzer |
| `search_suggestions` | Autocomplete and suggestions |

### Key Design Decisions

1. **Denormalization** for read performance (min_price, max_price, total_sold on products)
2. **Event Sourcing** for audit trail and rebuild capability
3. **Vietnamese analyzer** in Elasticsearch for proper tokenization
4. **Faceted search** with nested attributes
5. **Personalization hooks** ready for AI recommendations
