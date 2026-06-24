# Module 02: Domain Model

> **Status:** Module 2 of 14 - **✅ COMPLETED** (June 23, 2026)
> **Prerequisites:** Module 01 (Project Foundation)
> **Dependents:** All other modules
> **Estimated Effort:** 1 person-day

> ## Implementation Note
> ✅ **Implemented June 23, 2026.** All files compiled successfully with zero infrastructure dependencies in `domain/`.
>
> **Domain Layer Rule (verified):** `domain/` package KHÔNG được phụ thuộc vào bất kỳ infrastructure nào (Elasticsearch, Kafka, Spring). Chỉ chứa plain Java records.

---

## 1. Overview

This module defines:
- **ES Document Records** (ProductDocument, CategoryDocument, SuggestionDocument) — mapping 1:1 với ES index mapping
- **Domain Events** (CloudEventEnvelope + 9 event types) — Kafka event types từ Product/Review Service
- **Custom Domain Exceptions** (SearchException, InvalidCursorException)

All classes trong `domain/` package dùng **Java records** (immutable, no infrastructure deps).

---

## 2. ES Document Records

### 2.1 File: `domain/document/ProductDocument.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/document/ProductDocument.java`

- [x] Create `ProductDocument` Java record mapping 1:1 với `products_v1` index
- [x] Fields (theo [Elasticsearch Schema §2.2](../search_elasticsearch_schema.md#22-field-mapping-document-schema-denormalized-sku)):

```java
package com.ecommerce.search.domain.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductDocument(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("product_id") String productId,
    @JsonProperty("category_id") String categoryId,
    @JsonProperty("category_name") String categoryName,
    @JsonProperty("category_path") String categoryPath,
    String name,
    String description,
    @JsonProperty("thumbnail_url") String thumbnailUrl,
    @JsonProperty("product_status") String productStatus,
    @JsonProperty("sku_status") String skuStatus,
    @JsonProperty("searchable_attributes") List<String> searchableAttributes,
    @JsonProperty("variant_attributes") List<String> variantAttributes,
    @JsonProperty("original_price") Long originalPrice,
    @JsonProperty("promotional_price") Long promotionalPrice,
    @JsonProperty("best_price") Long bestPrice,
    @JsonProperty("is_in_stock") Boolean isInStock,
    @JsonProperty("has_promotion") Boolean hasPromotion,
    @JsonProperty("is_deleted") Boolean isDeleted,
    @JsonProperty("deleted_at") Instant deletedAt,
    @JsonProperty("product_rating_avg") Float productRatingAvg,
    @JsonProperty("product_rating_count") Integer productRatingCount,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonProperty("indexed_at") Instant indexedAt,
    @JsonProperty("last_event_id") String lastEventId,
    Instant version
) {
    /**
     * Compute is_in_stock from current state.
     * is_in_stock = !is_deleted AND product_status == 'ACTIVE' AND sku_status == 'ACTIVE'
     */
    public static boolean computeIsInStock(boolean isDeleted, String productStatus, String skuStatus) {
        return !isDeleted
            && "ACTIVE".equals(productStatus)
            && "ACTIVE".equals(skuStatus);
    }
}
```

> **Lưu ý:** Records dùng `@JsonProperty` cho snake_case mapping. Sử dụng `Long` (not `long`) cho nullable price fields. Sử dụng `Boolean` cho nullable boolean fields.

### 2.2 File: `domain/document/CategoryDocument.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/document/CategoryDocument.java`

- [x] Create `CategoryDocument` Java record mapping 1:1 với `categories_v1` index

```java
package com.ecommerce.search.domain.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryDocument(
    @JsonProperty("category_id") String categoryId,
    String name,
    String slug,
    @JsonProperty("parent_id") String parentId,
    Integer level,
    @JsonProperty("is_leaf") Boolean isLeaf,
    String path,
    @JsonProperty("product_count") Long productCount,
    @JsonProperty("is_deleted") Boolean isDeleted,
    @JsonProperty("deleted_at") Instant deletedAt,
    Instant version
) {}
```

### 2.3 File: `domain/document/SuggestionDocument.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/document/SuggestionDocument.java`

- [x] Create `SuggestionDocument` Java record mapping 1:1 với `search_suggestions_v1` index
- [x] `product_count` chỉ dùng cho CATEGORY type (Phase 21+)

```java
package com.ecommerce.search.domain.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SuggestionDocument(
    @JsonProperty("suggestion_id") String suggestionId,
    String text,
    @JsonProperty("text_normalized") String textNormalized,
    String type,         // PRODUCT | CATEGORY | BRAND | etc.
    String source,       // PRODUCTS_ALIAS | CATEGORIES_ALIAS
    String scope,        // GLOBAL
    @JsonProperty("category_id") String categoryId,
    @JsonProperty("product_id") String productId,
    Long weight,
    @JsonProperty("product_rating_avg") Float productRatingAvg,
    @JsonProperty("product_rating_count") Integer productRatingCount,
    @JsonProperty("product_count") Long productCount,  // chỉ dùng cho CATEGORY type
    @JsonProperty("build_version") Long buildVersion,
    @JsonProperty("updated_at") Instant updatedAt
) {}
```

---

## 3. CloudEvents Envelope

### 3.1 File: `domain/event/CloudEventEnvelope.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/event/CloudEventEnvelope.java`

- [x] Create CloudEvents envelope record (theo [Service Contract Matrix §0.2](../../_contracts/CONTRACT_MATRIX.md#02-kafka-envelope-cloudevents-wrapper))
- [x] Fields: `event_id`, `event_type`, `aggregate_type`, `aggregate_id`, `correlation_id`, `business_id`, `timestamp`, `source`, `schema_version`, `payload`

```java
package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudEventEnvelope(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("event_type") String eventType,
    @JsonProperty("aggregate_type") String aggregateType,
    @JsonProperty("aggregate_id") String aggregateId,
    @JsonProperty("correlation_id") String correlationId,
    @JsonProperty("business_id") String businessId,
    Instant timestamp,
    String source,
    @JsonProperty("schema_version") Integer schemaVersion,
    Map<String, Object> payload
) {}
```

> **Important:** `@JsonIgnoreProperties(ignoreUnknown = true)` để chấp nhận forward-compatible unknown fields (theo CONTRACT_MATRIX §7).

---

## 4. Domain Event Types

### 4.1 File: `domain/event/ProductCreatedEvent.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/event/ProductCreatedEvent.java`

- [x] Create event record for `PRODUCT_CREATED`
- [x] Fields: `product_id`, `category_id`, `name`, `description`, `status`, `attributes_json` (Map), `media` (List<Map>), `skus` (List<Map>), `created_at`, `updated_at`

```java
package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductCreatedEvent(
    @JsonProperty("product_id") String productId,
    @JsonProperty("category_id") String categoryId,
    String name,
    String description,
    String status,
    @JsonProperty("attributes_json") Map<String, Object> attributesJson,
    List<Map<String, Object>> media,
    List<Map<String, Object>> skus,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt
) {}
```

### 4.2 File: `domain/event/ProductUpdatedEvent.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/event/ProductUpdatedEvent.java`

- [x] Create event record for `PRODUCT_UPDATED`
- [x] Fields: `product_id`, `category_id`, `status`, `name`, `description`, `attributes_json`, `media`, `sku_ids` (List<String>), `updated_at`

### 4.3 File: `domain/event/SkuUpdatedEvent.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/event/SkuUpdatedEvent.java`

- [x] Create event record for `SKU_UPDATED`
- [x] Fields: `product_id`, `sku_id`, `sku_code`, `original_price`, `promotional_price`, `best_price`, `has_promotion`, `sku_status`, `variant_attributes_json`, `product_status_changed` (boolean), `new_product_status` (String, nullable), `change_source`, `updated_at`, `changed_fields` (List<String>)

```java
package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SkuUpdatedEvent(
    @JsonProperty("product_id") String productId,
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("sku_code") String skuCode,
    @JsonProperty("original_price") Long originalPrice,
    @JsonProperty("promotional_price") Long promotionalPrice,
    @JsonProperty("best_price") Long bestPrice,
    @JsonProperty("has_promotion") Boolean hasPromotion,
    @JsonProperty("sku_status") String skuStatus,
    @JsonProperty("variant_attributes_json") Map<String, Object> variantAttributesJson,
    @JsonProperty("product_status_changed") Boolean productStatusChanged,
    @JsonProperty("new_product_status") String newProductStatus,
    @JsonProperty("change_source") String changeSource,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonProperty("changed_fields") List<String> changedFields
) {}
```

> **Quan trọng (Phase 19):** Event này cũng mang `product_status_changed=true` + `new_product_status` khi Product thay đổi status. Search Service xử lý update `product_status` cho toàn bộ SKU documents của product (xem [Search Events §3.3](../search_events.md#33-product_status_changed-via-sku_updated-phase-19)).

### 4.4 File: `domain/event/SkuCreatedEvent.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/event/SkuCreatedEvent.java`

- [x] Create event record for `SKU_CREATED`
- [x] Fields: `product_id`, `name`, `category_id`, `sku_id`, `sku_code`, `original_price`, `promotional_price`, `best_price`, `has_promotion`, `sku_status`, `product_status`, `variant_attributes_json`, `media`, `updated_at`, `created_at`

### 4.5 File: `domain/event/ProductDeletedEvent.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/event/ProductDeletedEvent.java`

- [x] Create event record for `PRODUCT_DELETED`
- [x] Fields: `product_id`, `sku_ids` (List<String>), `updated_at`, `deleted_at`

### 4.6 File: `domain/event/CategoryCreatedEvent.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/event/CategoryCreatedEvent.java`

- [x] Create event record for `CATEGORY_CREATED`
- [x] Fields: `category_id`, `name`, `slug`, `parent_id`, `level`, `is_leaf`, `path`

### 4.7 File: `domain/event/CategoryUpdatedEvent.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/event/CategoryUpdatedEvent.java`

- [x] Create event record for `CATEGORY_UPDATED`
- [x] Fields: `category_id`, `new_name`, `new_path`

### 4.8 File: `domain/event/CategoryDeletedEvent.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/event/CategoryDeletedEvent.java`

- [x] Create event record for `CATEGORY_DELETED`
- [x] Fields: `category_id`, `descendant_category_ids` (List<String>), `updated_at`, `deleted_at`

### 4.9 File: `domain/event/ReviewMetricsUpdatedEvent.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/event/ReviewMetricsUpdatedEvent.java`

- [x] Create event record for `REVIEW_METRICS_UPDATED`
- [x] Fields: `product_id`, `average_rating` (Double), `total_reviews` (Integer), `updated_at`
- [x] **Ignore `total_with_media`** (Search Service không cần, downstream không dùng)

```java
package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewMetricsUpdatedEvent(
    @JsonProperty("product_id") String productId,
    @JsonProperty("average_rating") Double averageRating,
    @JsonProperty("total_reviews") Integer totalReviews,
    @JsonProperty("updated_at") Instant updatedAt
) {}
```

> **Lưu ý (Phase 21):** `total_with_media` có trong payload từ Review Service nhưng Search Service **không cần**. Worker chỉ đọc `product_id`, `average_rating`, `total_reviews`, `updated_at` — các field khác bị bỏ qua (nhờ `@JsonIgnoreProperties(ignoreUnknown = true)`).

---

## 5. Custom Domain Exceptions

### 5.1 File: `domain/exception/SearchException.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/exception/SearchException.java`

- [x] Create `SearchException extends RuntimeException`
- [x] Fields: `errorCode` (ErrorCode enum)
- [x] Constructors: `(ErrorCode, String message)`, `(ErrorCode, String message, Throwable cause)`

```java
package com.ecommerce.search.domain.exception;

import com.ecommerce.search.common.ErrorCode;
import lombok.Getter;

@Getter
public class SearchException extends RuntimeException {
    private final ErrorCode errorCode;

    public SearchException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SearchException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
```

### 5.2 File: `domain/exception/InvalidCursorException.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/exception/InvalidCursorException.java`

- [x] Create `InvalidCursorException extends RuntimeException`
- [x] Constructors: `(String message)`, `(String message, Throwable cause)`

```java
package com.ecommerce.search.domain.exception;

public class InvalidCursorException extends RuntimeException {
    public InvalidCursorException(String message) {
        super(message);
    }

    public InvalidCursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

---

## 6. DTOs (Response Objects)

### 6.1 File: `dto/ProductSearchResponse.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/dto/ProductSearchResponse.java`

- [x] Create response DTO for `/api/v1/search/products`
- [x] Fields: `total`, `total_skus`, `total_products`, `size`, `items` (List<ProductSearchItem>), `next_cursor`, `facets` (FacetResponse), `pagination_note`

```java
package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductSearchResponse(
    Long total,
    @JsonProperty("total_skus") Long totalSkus,
    @JsonProperty("total_products") Long totalProducts,
    Integer size,
    List<ProductSearchItem> items,
    @JsonProperty("next_cursor") String nextCursor,
    FacetResponse facets,
    @JsonProperty("pagination_note") String paginationNote
) {}
```

### 6.2 File: `dto/ProductSearchItem.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/dto/ProductSearchItem.java`

- [x] Create item DTO (theo [Search Workflow §2 response format](../search_workflow.md#2-api-tìm-kiếm-sản-phẩm-full-text-search--filters--facets))

```java
package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record ProductSearchItem(
    @JsonProperty("product_id") String productId,
    @JsonProperty("sku_id") String skuId,
    String name,
    @JsonProperty("thumbnail_url") String thumbnailUrl,
    @JsonProperty("original_price") Long originalPrice,
    @JsonProperty("best_price") Long bestPrice,
    @JsonProperty("discount_percentage") Integer discountPercentage,
    @JsonProperty("product_rating_avg") Float productRatingAvg,
    @JsonProperty("is_in_stock") Boolean isInStock,
    @JsonProperty("matching_skus") List<MatchingSku> matchingSkus
) {
    @Builder
    public record MatchingSku(
        @JsonProperty("sku_id") String skuId,
        @JsonProperty("best_price") Long bestPrice,
        @JsonProperty("variant_attributes") java.util.List<String> variantAttributes
    ) {}
}
```

### 6.3 File: `dto/SuggestResponse.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/dto/SuggestResponse.java`

- [x] Create response DTO for `/api/v1/search/suggest`
- [x] Fields: `suggestions` (List<SuggestItem>)

```java
package com.ecommerce.search.dto;

import java.util.List;

public record SuggestResponse(List<SuggestItem> suggestions) {
    public record SuggestItem(String text, String type, Long weight) {}
}
```

### 6.4 File: `dto/CategoryTreeResponse.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/dto/CategoryTreeResponse.java`

- [x] Create response DTO for `/api/v1/search/categories`
- [x] Fields: `categories` (List<CategoryNode>) — flat list với parent_id reference

```java
package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

@Builder
public record CategoryTreeResponse(List<CategoryNode> categories) {
    @Builder
    public record CategoryNode(
        @JsonProperty("category_id") String categoryId,
        String name,
        String slug,
        @JsonProperty("parent_id") String parentId,
        Integer level,
        @JsonProperty("is_leaf") Boolean isLeaf,
        String path,
        @JsonProperty("product_count") Long productCount
    ) {}
}
```

### 6.5 File: `dto/FacetResponse.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/dto/FacetResponse.java`

- [x] Create response DTO for `/api/v1/search/facets/{id}`
- [x] Fields: `category_id`, `count_semantics`, `filters` (List<FilterGroup>), `total_products`

```java
package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

@Builder
public record FacetResponse(
    @JsonProperty("category_id") String categoryId,
    @JsonProperty("count_semantics") String countSemantics,
    List<FilterGroup> filters,
    @JsonProperty("total_products") Long totalProducts
) {
    @Builder
    public record FilterGroup(
        String name,
        String type,  // CHECKBOX | RANGE
        List<FilterOption> options
    ) {}

    @Builder
    public record FilterOption(
        String value,
        String label,
        Long count
    ) {}
}
```

### 6.6 File: `dto/ReindexTriggerResponse.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/dto/ReindexTriggerResponse.java`

- [x] Create response DTO for `POST /internal/v1/search/reindex` (202 Accepted)

```java
package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record ReindexTriggerResponse(
    @JsonProperty("run_id") String runId,
    String status,         // STARTED
    @JsonProperty("started_at") String startedAt,
    @JsonProperty("estimated_duration_minutes") Integer estimatedDurationMinutes,
    @JsonProperty("status_url") String statusUrl
) {}
```

### 6.7 File: `dto/ReindexStatusResponse.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/dto/ReindexStatusResponse.java`

- [x] Create response DTO for `GET /internal/v1/search/reindex/{run_id}`

```java
package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.Map;

@Builder
public record ReindexStatusResponse(
    @JsonProperty("run_id") String runId,
    String status,    // BUILDING | CATCHING_UP | CUTOVER | COMPLETED | FAILED | ROLLED_BACK
    @JsonProperty("current_phase") String currentPhase,
    Map<String, Object> progress,
    @JsonProperty("started_at") String startedAt,
    @JsonProperty("finished_at") String finishedAt,
    @JsonProperty("error_message") String errorMessage
) {}
```

### 6.8 File: `dto/ErrorResponse.java`

- [x] Already defined in Module 01 (xem [01-project-foundation.md §6.4](01-project-foundation.md#64-file-errorresponsejava))

---

## 7. Domain Layer Acceptance Criteria

- [x] All `domain/document/*` files compile không lỗi
- [x] All `domain/event/*` files compile không lỗi
- [x] All records sử dụng `@JsonProperty` cho snake_case mapping
- [x] All CloudEvents events sử dụng `@JsonIgnoreProperties(ignoreUnknown = true)`
- [x] All `domain/exception/*` files compile không lỗi
- [x] All `dto/*` files compile không lỗi
- [x] Records là **immutable** (Java records)
- [x] **No infrastructure dependencies** trong `domain/` package (no `org.springframework.*`, no `co.elastic.clients.*`)

---

## 8. Implementation File Index

| # | File | Status |
|---|------|--------|
| 1 | `domain/document/ProductDocument.java` | ✅ Completed |
| 2 | `domain/document/CategoryDocument.java` | ✅ Completed |
| 3 | `domain/document/SuggestionDocument.java` | ✅ Completed |
| 4 | `domain/event/CloudEventEnvelope.java` | ✅ Completed |
| 5 | `domain/event/ProductCreatedEvent.java` | ✅ Completed |
| 6 | `domain/event/ProductUpdatedEvent.java` | ✅ Completed |
| 7 | `domain/event/SkuUpdatedEvent.java` | ✅ Completed |
| 8 | `domain/event/SkuCreatedEvent.java` | ✅ Completed |
| 9 | `domain/event/ProductDeletedEvent.java` | ✅ Completed |
| 10 | `domain/event/CategoryCreatedEvent.java` | ✅ Completed |
| 11 | `domain/event/CategoryUpdatedEvent.java` | ✅ Completed |
| 12 | `domain/event/CategoryDeletedEvent.java` | ✅ Completed |
| 13 | `domain/event/ReviewMetricsUpdatedEvent.java` | ✅ Completed |
| 14 | `domain/exception/SearchException.java` | ✅ Completed |
| 15 | `domain/exception/InvalidCursorException.java` | ✅ Completed |
| 16 | `dto/ProductSearchResponse.java` | ✅ Completed |
| 17 | `dto/ProductSearchItem.java` | ✅ Completed |
| 18 | `dto/SuggestResponse.java` | ✅ Completed |
| 19 | `dto/CategoryTreeResponse.java` | ✅ Completed |
| 20 | `dto/FacetResponse.java` | ✅ Completed |
| 21 | `dto/ReindexTriggerResponse.java` | ✅ Completed |
| 22 | `dto/ReindexStatusResponse.java` | ✅ Completed |
