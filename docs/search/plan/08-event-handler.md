# Module 08: Event Handler (Dispatch by event_type)

> **Status:** Module 8 of 14 - **NOT YET IMPLEMENTED**
> **Prerequisites:** Module 07 (Kafka Consumer Setup)
> **Dependents:** Module 09 (Product Count Worker uses EventHandler for realtime updates)
> **Estimated Effort:** 3 person-days

> ## Implementation Note
> Chưa implement. Plan chi tiết bên dưới. Khi implement, đánh dấu `[x]` cho từng task.
>
> **Module phức tạp thứ 2** vì phải xử lý 9 event types, mỗi loại có logic transformation riêng. Tất cả logic theo [Search Events](../search_events.md).

---

## 1. Overview

This module implements the core event processing logic. EventHandler class dispatches theo event_type tới các handler method tuong ứng:

| Event Type | Handler | Module 09 Trigger? |
|---|---|---|
| `PRODUCT_CREATED` | handleProductCreated | Yes (tăng count nếu ACTIVE) |
| `PRODUCT_UPDATED` | handleProductUpdated | No |
| `SKU_UPDATED` | handleSkuUpdated | Yes (nếu product_status_changed=true) |
| `SKU_CREATED` | handleSkuCreated | Yes (nếu product ACTIVE) |
| `PRODUCT_DELETED` | handleProductDeleted | Yes (giảm count nếu ACTIVE) |
| `CATEGORY_CREATED` | handleCategoryCreated | No |
| `CATEGORY_UPDATED` | handleCategoryUpdated | No |
| `CATEGORY_DELETED` | handleCategoryDeleted | No |
| `REVIEW_METRICS_UPDATED` | handleReviewMetricsUpdated | No |

---

## 2. EventHandler (Main Dispatcher)

### 2.1 File: `worker/EventHandler.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/worker/EventHandler.java`

- [ ] Create @Component class
- [ ] Inject ProductEventHandlers, CategoryEventHandlers, ReviewEventHandlers
- [ ] Method: handle(CloudEventEnvelope envelope) - dispatch by event_type
- [ ] Use switch expression to route to handler method

```java
package com.ecommerce.search.worker;

import com.ecommerce.common.events.CloudEventEnvelope;
import com.ecommerce.search.common.EventType;
import com.ecommerce.search.worker.handler.ProductEventHandlers;
import com.ecommerce.search.worker.handler.CategoryEventHandlers;
import com.ecommerce.search.worker.handler.ReviewEventHandlers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventHandler {

    private final ProductEventHandlers productHandlers;
    private final CategoryEventHandlers categoryHandlers;
    private final ReviewEventHandlers reviewHandlers;

    public void handle(CloudEventEnvelope envelope) {
        String eventType = envelope.eventType();
        String eventId = envelope.eventId();
        MDC.put("event_type", eventType);
        MDC.put("event_id", eventId);
        try {
            EventType type = EventType.fromString(eventType);
            if (type == null) {
                log.warn("Unknown event type, skipping: {}", eventType);
                return;
            }

            switch (type) {
                case PRODUCT_CREATED -> productHandlers.handleProductCreated(envelope);
                case PRODUCT_UPDATED -> productHandlers.handleProductUpdated(envelope);
                case SKU_UPDATED -> productHandlers.handleSkuUpdated(envelope);
                case SKU_CREATED -> productHandlers.handleSkuCreated(envelope);
                case PRODUCT_DELETED -> productHandlers.handleProductDeleted(envelope);
                case CATEGORY_CREATED -> categoryHandlers.handleCategoryCreated(envelope);
                case CATEGORY_UPDATED -> categoryHandlers.handleCategoryUpdated(envelope);
                case CATEGORY_DELETED -> categoryHandlers.handleCategoryDeleted(envelope);
                case REVIEW_METRICS_UPDATED -> reviewHandlers.handleReviewMetricsUpdated(envelope);
                default -> log.warn("Legacy event type {}, skipping", type);
            }
        } catch (Exception e) {
            log.error("Failed to handle event: type={}, id={}", eventType, eventId, e);
            throw e;
        } finally {
            MDC.remove("event_type");
            MDC.remove("event_id");
        }
    }
}
```

---

## 3. Product Event Handlers

### 3.1 File: `worker/handler/ProductEventHandlers.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/worker/handler/ProductEventHandlers.java`

- [ ] Create @Component class
- [ ] Inject ProductSearchRepository, CategoryRepository, ObjectMapper, ProductCountUpdater (Module 09)
- [ ] Implement 5 handler methods (see below)

```java
package com.ecommerce.search.worker.handler;

import com.ecommerce.common.events.CloudEventEnvelope;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.event.*;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.repository.CategoryRepository;
import com.ecommerce.search.repository.ProductSearchRepository;
import com.ecommerce.search.worker.ProductCountUpdater;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventHandlers {

    private final ProductSearchRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductCountUpdater productCountUpdater;
    private final ObjectMapper objectMapper;

    /**
     * Handle PRODUCT_CREATED event.
     * 1. Parse payload
     * 2. Validate skus[] is non-empty + has best_price + has_promotion
     * 3. Lookup category for enrichment
     * 4. Transform attributes_json -> searchable_attributes tokens
     * 5. Resolve thumbnail_url
     * 6. Derive is_in_stock
     * 7. Index each SKU document
     * 8. Trigger product_count update if product ACTIVE
     */
    public void handleProductCreated(CloudEventEnvelope envelope) {
        ProductCreatedEvent event = deserialize(envelope, ProductCreatedEvent.class);

        // Validate
        if (event.skus() == null || event.skus().isEmpty()) {
            log.warn("PRODUCT_CREATED has empty skus, skipping: product_id={}", event.productId());
            return;
        }
        for (Map<String, Object> sku : event.skus()) {
            if (sku.get("best_price") == null || sku.get("has_promotion") == null) {
                log.warn("SKU missing best_price or has_promotion, skipping: product_id={}", event.productId());
                return;
            }
        }

        // Enrichment
        String categoryName = null;
        String categoryPath = null;
        if (event.categoryId() != null) {
            var category = categoryRepository.getById(event.categoryId());
            if (category != null) {
                categoryName = category.name();
                categoryPath = category.path();
            } else {
                log.warn("Category lookup miss: category_id={}", event.categoryId());
                // TODO: increment search_category_enrichment_miss_total metric
            }
        }

        // Index each SKU
        boolean allActive = "ACTIVE".equals(event.status());
        Instant now = Instant.now();
        for (Map<String, Object> skuMap : event.skus()) {
            ProductDocument doc = buildProductDocument(event, skuMap, categoryName, categoryPath, now);
            productRepository.index(doc);
        }

        // Trigger product_count update if product ACTIVE
        if (allActive && event.categoryId() != null) {
            productCountUpdater.incrementCount(event.categoryId(), envelope.eventId());
        }
    }

    /**
     * Handle PRODUCT_UPDATED event.
     * Update product-level fields on all SKU documents.
     */
    public void handleProductUpdated(CloudEventEnvelope envelope) {
        ProductUpdatedEvent event = deserialize(envelope, ProductUpdatedEvent.class);
        if (event.skuIds() == null || event.skuIds().isEmpty()) {
            return;
        }

        // Enrichment if category_id changed
        String categoryName = null;
        String categoryPath = null;
        if (event.categoryId() != null) {
            var category = categoryRepository.getById(event.categoryId());
            if (category != null) {
                categoryName = category.name();
                categoryPath = category.path();
            }
        }

        // Build partial update script
        Map<String, Object> fields = new HashMap<>();
        if (event.name() != null) fields.put("name", event.name());
        if (event.description() != null) fields.put("description", event.description());
        if (event.categoryId() != null) {
            fields.put("category_id", event.categoryId());
            fields.put("category_name", categoryName);
            fields.put("category_path", categoryPath);
        }
        // ... transform attributes_json, resolve thumbnail_url ...

        String scriptSource = buildPatchScript();
        for (String skuId : event.skuIds()) {
            Map<String, Object> params = Map.of(
                "updated_at", event.updatedAt().toString(),
                "indexed_at", Instant.now().toString(),
                "event_id", envelope.eventId(),
                "fields", fields
            );
            productRepository.updateByScript(skuId, scriptSource, params);
        }
    }

    /**
     * Handle SKU_UPDATED event.
     * - Partial update per changed_fields
     * - If product_status_changed=true: trigger product_count update AND _update_by_query for product_status
     */
    public void handleSkuUpdated(CloudEventEnvelope envelope) {
        SkuUpdatedEvent event = deserialize(envelope, SkuUpdatedEvent.class);

        // If product_status_changed=true: handle via product-wide update
        if (Boolean.TRUE.equals(event.productStatusChanged())) {
            handleProductStatusChanged(event, envelope);
        }

        // Partial update the SKU document with changed_fields
        Map<String, Object> fields = buildFieldsFromChangedFields(event);
        String scriptSource = buildPatchScript();
        Map<String, Object> params = Map.of(
            "updated_at", event.updatedAt().toString(),
            "indexed_at", Instant.now().toString(),
            "event_id", envelope.eventId(),
            "fields", fields
        );
        productRepository.updateByScript(event.skuId(), scriptSource, params);

        // Trigger product_count update if needed
        if (Boolean.TRUE.equals(event.productStatusChanged())) {
            String newStatus = event.newProductStatus();
            if ("ACTIVE".equals(newStatus) && event.categoryId() != null) {
                productCountUpdater.incrementCount(event.categoryId(), envelope.eventId());
            } else if ("HIDDEN".equals(newStatus) && event.categoryId() != null) {
                productCountUpdater.decrementCount(event.categoryId(), envelope.eventId());
            }
        }
    }

    /**
     * Handle product_status_changed via SKU_UPDATED.
     * Uses _update_by_query to update all SKU docs of the product.
     * (Phase 19: PRODUCT_STATUS_CHANGED merged into SKU_UPDATED)
     */
    private void handleProductStatusChanged(SkuUpdatedEvent event, CloudEventEnvelope envelope) {
        String scriptSource = """
            ctx._source.product_status = params.new_status;
            ctx._source.is_in_stock = (!ctx._source.is_deleted && ctx._source.product_status == 'ACTIVE' && ctx._source.sku_status == 'ACTIVE');
            ctx._source.indexed_at = params.indexed_at;
            ctx._source.last_event_id = params.event_id;
            """;
        Map<String, Object> params = Map.of(
            "new_status", event.newProductStatus(),
            "indexed_at", Instant.now().toString(),
            "event_id", envelope.eventId()
        );
        // Build query: product_id == event.productId()
        productRepository.updateByQueryForProduct(event.productId(), scriptSource, params);
    }

    /**
     * Handle SKU_CREATED event.
     * Similar to PRODUCT_CREATED but for a single new SKU.
     */
    public void handleSkuCreated(CloudEventEnvelope envelope) {
        SkuCreatedEvent event = deserialize(envelope, SkuCreatedEvent.class);

        // Enrichment
        String categoryName = null;
        String categoryPath = null;
        if (event.categoryId() != null) {
            var category = categoryRepository.getById(event.categoryId());
            if (category != null) {
                categoryName = category.name();
                categoryPath = category.path();
            }
        }

        // Build document
        ProductDocument doc = ProductDocument.builder()
            .skuId(event.skuId())
            .productId(event.productId())
            .categoryId(event.categoryId())
            .categoryName(categoryName)
            .categoryPath(categoryPath)
            .name(event.name())
            .skuStatus(event.skuStatus())
            .productStatus(event.productStatus())
            .originalPrice(toLong(event.skuId(), "original_price", event))  // from event map
            .promotionalPrice(toLong(event.skuId(), "promotional_price", event))
            .bestPrice(toLong(event.skuId(), "best_price", event))
            .hasPromotion(Boolean.TRUE.equals(event.has_promotion()))
            .isInStock(ProductDocument.computeIsInStock(false, event.productStatus(), event.skuStatus()))
            .isDeleted(false)
            .variantAttributes(transformVariantAttributes(event.variantAttributesJson()))
            .thumbnailUrl(resolveThumbnailUrlFromMap(event.media()))
            .createdAt(event.createdAt())
            .updatedAt(event.updatedAt())
            .indexedAt(Instant.now())
            .lastEventId(envelope.eventId())
            .version(event.createdAt())  // SKU_CREATED uses created_at as version
            .build();

        productRepository.index(doc);

        // Trigger product_count update if product ACTIVE
        if ("ACTIVE".equals(event.productStatus()) && event.categoryId() != null) {
            productCountUpdater.incrementCount(event.categoryId(), envelope.eventId());
        }
    }

    /**
     * Handle PRODUCT_DELETED event.
     * Tombstone all SKUs of the product.
     */
    public void handleProductDeleted(CloudEventEnvelope envelope) {
        ProductDeletedEvent event = deserialize(envelope, ProductDeletedEvent.class);
        if (event.skuIds() == null) return;

        String scriptSource = """
            if (ctx._source.version != null && params.updated_at <= ctx._source.version) {
              ctx.op = 'none';
              return;
            }
            ctx._source.is_deleted = true;
            ctx._source.deleted_at = params.deleted_at;
            ctx._source.is_in_stock = false;
            ctx._source.version = params.updated_at;
            ctx._source.indexed_at = params.indexed_at;
            ctx._source.last_event_id = params.event_id;
            """;
        Map<String, Object> params = Map.of(
            "updated_at", event.updatedAt().toString(),
            "deleted_at", event.updatedAt().toString(),
            "indexed_at", Instant.now().toString(),
            "event_id", envelope.eventId()
        );
        for (String skuId : event.skuIds()) {
            productRepository.updateByScript(skuId, scriptSource, params);
        }

        // Trigger product_count update (decrement if was ACTIVE)
        if (event.categoryId() != null) {
            productCountUpdater.decrementCount(event.categoryId(), envelope.eventId());
        }
    }

    // Helper methods
    private <T> T deserialize(CloudEventEnvelope envelope, Class<T> type) {
        try {
            return objectMapper.convertValue(envelope.payload(), type);
        } catch (Exception e) {
            throw new SearchException(ErrorCode.INTERNAL_ERROR, "Failed to deserialize event", e);
        }
    }

    private ProductDocument buildProductDocument(ProductCreatedEvent event, Map<String, Object> skuMap, String categoryName, String categoryPath, Instant now) {
        // Build full ProductDocument from event + SKU map
        // (skus[].status -> skus[].sku_status backward compat)
        String skuStatus = skuMap.getOrDefault("sku_status", skuMap.get("status")).toString();
        Long originalPrice = toLong(skuMap, "original_price");
        Long promotionalPrice = toLong(skuMap, "promotional_price");
        Long bestPrice = toLong(skuMap, "best_price");
        Boolean hasPromotion = (Boolean) skuMap.get("has_promotion");

        boolean isInStock = ProductDocument.computeIsInStock(false, event.status(), skuStatus);

        return ProductDocument.builder()
            .skuId(skuMap.get("sku_id").toString())
            .productId(event.productId())
            .categoryId(event.categoryId())
            .categoryName(categoryName)
            .categoryPath(categoryPath)
            .name(event.name())
            .description(event.description())
            .thumbnailUrl(resolveThumbnailUrl(skuMap, event.media()))
            .productStatus(event.status())
            .skuStatus(skuStatus)
            .searchableAttributes(transformAttributes(event.attributesJson()))
            .variantAttributes(transformVariantAttributes(skuMap.get("variant_attributes_json")))
            .originalPrice(originalPrice)
            .promotionalPrice(promotionalPrice)
            .bestPrice(bestPrice)
            .hasPromotion(Boolean.TRUE.equals(hasPromotion))
            .isInStock(isInStock)
            .isDeleted(false)
            .createdAt(event.createdAt())
            .updatedAt(event.updatedAt())
            .indexedAt(now)
            .lastEventId(event.eventId())
            .version(event.updatedAt())
            .build();
    }

    private List<String> transformAttributes(Map<String, Object> attributesJson) {
        if (attributesJson == null) return List.of();
        return attributesJson.entrySet().stream()
            .map(e -> e.getKey() + ":" + e.getValue())
            .toList();
    }

    private List<String> transformVariantAttributes(Object variantAttributesJson) {
        if (variantAttributesJson == null) return List.of();
        // variantAttributesJson is Map<String, Object> like {"color": "Đen", "storage": "256GB"}
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) variantAttributesJson;
        return map.entrySet().stream()
            .map(e -> e.getKey() + ":" + e.getValue())
            .toList();
    }

    private String resolveThumbnailUrl(Map<String, Object> skuMap, List<Map<String, Object>> productMedia) {
        // Priority: product.media[0].thumbnail_url > product.media[0].url > sku.media[0].url
        if (productMedia != null && !productMedia.isEmpty()) {
            Map<String, Object> firstMedia = productMedia.get(0);
            if (firstMedia.get("thumbnail_url") != null) {
                return firstMedia.get("thumbnail_url").toString();
            }
            if (firstMedia.get("url") != null) {
                return firstMedia.get("url").toString();
            }
        }
        // Fallback: SKU-level media
        Object skuMedia = skuMap.get("media");
        if (skuMedia instanceof List<?> list && !list.isEmpty()) {
            Map<String, Object> firstSkuMedia = (Map<String, Object>) list.get(0);
            if (firstSkuMedia.get("url") != null) {
                return firstSkuMedia.get("url").toString();
            }
        }
        return null;
    }

    private String resolveThumbnailUrlFromMap(Object media) {
        if (media instanceof List<?> list && !list.isEmpty()) {
            Map<String, Object> first = (Map<String, Object>) list.get(0);
            if (first.get("url") != null) return first.get("url").toString();
            if (first.get("thumbnail_url") != null) return first.get("thumbnail_url").toString();
        }
        return null;
    }

    private Long toLong(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    private Long toLong(String skuId, String key, Object event) {
        // For SKU_CREATED, fields are in event payload directly
        // (Implementation: parse from event)
        return null;  // TODO
    }

    private String buildPatchScript() {
        return """
            if (ctx._source.version != null && params.updated_at <= ctx._source.version) {
              ctx.op = 'none';
              return;
            }
            for (entry in params.fields) {
              ctx._source[entry.key] = entry.value;
            }
            ctx._source.version = params.updated_at;
            ctx._source.indexed_at = params.indexed_at;
            ctx._source.last_event_id = params.event_id;
            """;
    }

    private Map<String, Object> buildFieldsFromChangedFields(SkuUpdatedEvent event) {
        Map<String, Object> fields = new HashMap<>();
        if (event.changedFields() == null) return fields;
        for (String field : event.changedFields()) {
            switch (field) {
                case "original_price" -> fields.put("original_price", event.originalPrice());
                case "promotional_price" -> fields.put("promotional_price", event.promotionalPrice());
                case "best_price" -> fields.put("best_price", event.bestPrice());
                case "has_promotion" -> fields.put("has_promotion", event.hasPromotion());
                case "sku_status" -> fields.put("sku_status", event.skuStatus());
                case "variant_attributes" -> fields.put("variant_attributes", transformVariantAttributes(event.variantAttributesJson()));
            }
        }
        return fields;
    }
}
```

> **Note:** File này dài. Khi implement, có thể tách thành nhiều class nhỏ hơn (ProductCreatedHandler, ProductUpdatedHandler, etc.) để dễ maintain.

---

## 4. Category Event Handlers

### 4.1 File: `worker/handler/CategoryEventHandlers.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/worker/handler/CategoryEventHandlers.java`

- [ ] Implement 3 handlers: CATEGORY_CREATED, CATEGORY_UPDATED, CATEGORY_DELETED

```java
package com.ecommerce.search.worker.handler;

import com.ecommerce.common.events.CloudEventEnvelope;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.event.*;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.repository.CategoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CategoryEventHandlers {

    private final CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper;

    public void handleCategoryCreated(CloudEventEnvelope envelope) {
        CategoryCreatedEvent event = deserialize(envelope, CategoryCreatedEvent.class);

        if (event.path() == null) {
            log.warn("CATEGORY_CREATED missing required field 'path', skipping: category_id={}", event.categoryId());
            return;
        }

        CategoryDocument doc = CategoryDocument.builder()
            .categoryId(event.categoryId())
            .name(event.name())
            .slug(event.slug())
            .parentId(event.parentId())
            .level(event.level())
            .isLeaf(event.isLeaf())
            .path(event.path())
            .productCount(0L)
            .isDeleted(false)
            .version(Instant.now())
            .build();

        categoryRepository.index(doc);
    }

    public void handleCategoryUpdated(CloudEventEnvelope envelope) {
        CategoryUpdatedEvent event = deserialize(envelope, CategoryUpdatedEvent.class);

        String scriptSource = """
            if (ctx._source.version != null && params.updated_at <= ctx._source.version) {
              ctx.op = 'none';
              return;
            }
            ctx._source.name = params.new_name;
            ctx._source.path = params.new_path;
            ctx._source.version = params.updated_at;
            ctx._source.indexed_at = params.indexed_at;
            ctx._source.last_event_id = params.event_id;
            """;
        Map<String, Object> params = Map.of(
            "new_name", event.newName(),
            "new_path", event.newPath(),
            "updated_at", Instant.now().toString(),
            "indexed_at", Instant.now().toString(),
            "event_id", envelope.eventId()
        );
        categoryRepository.updateByScript(event.categoryId(), scriptSource, params);
        // TODO: also update products_write by category_id
    }

    public void handleCategoryDeleted(CloudEventEnvelope envelope) {
        CategoryDeletedEvent event = deserialize(envelope, CategoryDeletedEvent.class);

        String scriptSource = """
            ctx._source.is_deleted = true;
            ctx._source.deleted_at = params.deleted_at;
            ctx._source.version = params.updated_at;
            ctx._source.indexed_at = params.indexed_at;
            ctx._source.last_event_id = params.event_id;
            """;
        Map<String, Object> params = Map.of(
            "deleted_at", event.deletedAt().toString(),
            "updated_at", event.updatedAt().toString(),
            "indexed_at", Instant.now().toString(),
            "event_id", envelope.eventId()
        );
        categoryRepository.updateByScript(event.categoryId(), scriptSource, params);
        // TODO: also nullify category_* on products_write by category_id
    }

    private <T> T deserialize(CloudEventEnvelope envelope, Class<T> type) {
        try {
            return objectMapper.convertValue(envelope.payload(), type);
        } catch (Exception e) {
            throw new SearchException(ErrorCode.INTERNAL_ERROR, "Failed to deserialize event", e);
        }
    }
}
```

---

## 5. Review Event Handlers

### 5.1 File: `worker/handler/ReviewEventHandlers.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/worker/handler/ReviewEventHandlers.java`

- [ ] Implement REVIEW_METRICS_UPDATED handler
- [ ] Use _update_by_query (no script guard - partition ordering)

```java
package com.ecommerce.search.worker.handler;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import com.ecommerce.common.events.CloudEventEnvelope;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.event.ReviewMetricsUpdatedEvent;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.repository.es.UpdateByQueryExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewEventHandlers {

    private final UpdateByQueryExecutor updateByQueryExecutor;
    private final SearchProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Phase 21: No script guard. Partition ordering ensures correctness.
     * Ignore total_with_media field.
     */
    public void handleReviewMetricsUpdated(CloudEventEnvelope envelope) {
        ReviewMetricsUpdatedEvent event = deserialize(envelope, ReviewMetricsUpdatedEvent.class);

        String scriptSource = """
            ctx._source.product_rating_avg = params.average_rating;
            ctx._source.product_rating_count = params.total_reviews;
            ctx._source.indexed_at = params.indexed_at;
            ctx._source.last_event_id = params.event_id;
            """;
        Map<String, Object> params = Map.of(
            "average_rating", event.averageRating(),
            "total_reviews", event.totalReviews(),
            "indexed_at", Instant.now().toString(),
            "event_id", envelope.eventId()
        );

        Query query = Query.of(q -> q.term(t -> t.field("product_id").value(event.productId())));

        UpdateByQueryResponse response = updateByQueryExecutor.updateByQuery(
            properties.getEs().getIndexAliases().getProducts(),
            query,
            scriptSource,
            params
        );

        log.debug("REVIEW_METRICS_UPDATED: product_id={}, updated={}, total={}",
            event.productId(), response.updated(), response.total());
    }

    private <T> T deserialize(CloudEventEnvelope envelope, Class<T> type) {
        try {
            return objectMapper.convertValue(envelope.payload(), type);
        } catch (Exception e) {
            throw new SearchException(ErrorCode.INTERNAL_ERROR, "Failed to deserialize event", e);
        }
    }
}
```

---

## 6. Update ProductSearchRepository

### 6.1 Add method: `updateByQueryForProduct`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/repository/ProductSearchRepository.java`

Add method to support product-wide _update_by_query:

```java
public void updateByQueryForProduct(String productId, String scriptSource, Map<String, Object> params) {
    try {
        Query query = Query.of(q -> q.term(t -> t.field("product_id").value(productId)));
        esClient.updateByQuery(u -> u
            .index(properties.getEs().getIndexAliases().getProducts())
            .query(query)
            .script(s -> s.inline(i -> i.source(scriptSource).params(Map.of("params", params))))
            .refresh(true)
            .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
            .requestsPerSecond((float) properties.getEs().getUpdateByQueryThrottleRps())
        );
    } catch (Exception e) {
        log.error("Failed to update_by_query for product_id={}", productId, e);
        throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES update_by_query failed", e);
    }
}
```

---

## 7. Acceptance Criteria

- [ ] 9 handler methods implemented theo đúng spec trong [Search Events §3](../search_events.md)
- [ ] `PRODUCT_CREATED`: parse skus[], enrich category, transform attributes, index N docs
- [ ] `PRODUCT_UPDATED`: update product-level fields on all SKUs (loop sku_ids)
- [ ] `SKU_UPDATED`: handle product_status_changed via _update_by_query (no script guard)
- [ ] `SKU_CREATED`: use created_at as version (scripted_upsert)
- [ ] `PRODUCT_DELETED`: tombstone (is_deleted=true, is_in_stock=false)
- [ ] `CATEGORY_UPDATED`: update categories + _update_by_query on products
- [ ] `CATEGORY_DELETED`: tombstone + nullify category_* on products
- [ ] `REVIEW_METRICS_UPDATED`: _update_by_query no script guard
- [ ] All handlers log + metric + skip on errors (no throw, let error handler decide)
- [ ] `total_with_media` field ignored (Phase 21)
- [ ] Integration test với Testcontainers verify end-to-end

---

## 8. Implementation File Index

| # | File | Status |
|---|------|--------|
| 1 | `worker/EventHandler.java` | ⏳ Not yet |
| 2 | `worker/handler/ProductEventHandlers.java` | ⏳ Not yet |
| 3 | `worker/handler/CategoryEventHandlers.java` | ⏳ Not yet |
| 4 | `worker/handler/ReviewEventHandlers.java` | ⏳ Not yet |
| 5 | `repository/ProductSearchRepository.java` (add updateByQueryForProduct) | ⏳ Not yet |
| 6 | `worker/handler/ProductEventHandlersTest.java` | ⏳ Not yet |
| 7 | `worker/handler/CategoryEventHandlersTest.java` | ⏳ Not yet |
| 8 | `worker/handler/ReviewEventHandlersTest.java` | ⏳ Not yet |
| 9 | `worker/EventHandlerIT.java` (Testcontainers end-to-end) | ⏳ Not yet |
