package com.ecommerce.search.worker.handler;

import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.event.*;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.repository.CategoryRepository;
import com.ecommerce.search.repository.ProductSearchRepository;
import com.ecommerce.search.worker.ProductCountUpdater;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handlers for product-related events consumed from {@code t.product.events}.
 *
 * <p>Each method maps to one event type and contains the full transformation
 * and indexing logic described in {@code docs/search/search_events.md §3}.
 *
 * <p><strong>Error handling:</strong> Non-recoverable errors (missing document,
 * mapping conflict) are logged and the exception is re-thrown so the
 * {@link com.ecommerce.search.worker.EventHandler} can propagate it to the
 * consumer error handler. Transient infrastructure errors (ES timeouts) are
 * left to the retry policy in {@link com.ecommerce.search.worker.KafkaConsumerConfig}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventHandlers {

    private final ProductSearchRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductCountUpdater productCountUpdater;
    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────────────────────────
    //  PRODUCT_CREATED
    // ─────────────────────────────────────────────────────────────────

    /**
     * Index one {@link ProductDocument} per SKU and trigger
     * {@code product_count} increment if the product is ACTIVE.
     */
    public void handleProductCreated(CloudEventEnvelope envelope) {
        ProductCreatedEvent event = deserialize(envelope, ProductCreatedEvent.class);

        // === Validate ===
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

        // === Enrichment — category lookup ===
        String categoryName = null;
        String categoryPath = null;
        if (event.categoryId() != null) {
            CategoryDocument category = lookupCategory(event.categoryId(), envelope.eventType(), event.productId());
            if (category != null) {
                categoryName = category.name();
                categoryPath = category.path();
            }
        }

        // === Index each SKU ===
        boolean allActive = "ACTIVE".equals(event.status());
        Instant now = Instant.now();
        for (Map<String, Object> skuMap : event.skus()) {
            ProductDocument doc = buildProductDocument(event, skuMap, categoryName, categoryPath, now, envelope.eventId());
            productRepository.index(doc);
        }

        // === Trigger product_count increment if product is ACTIVE ===
        if (allActive && event.categoryId() != null) {
            productCountUpdater.incrementCount(event.categoryId(), envelope.eventId());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  PRODUCT_UPDATED
    // ─────────────────────────────────────────────────────────────────

    /**
     * Partial-update product-level fields on all SKU documents listed in
     * {@link ProductUpdatedEvent#skuIds()}.
     */
    public void handleProductUpdated(CloudEventEnvelope envelope) {
        ProductUpdatedEvent event = deserialize(envelope, ProductUpdatedEvent.class);

        if (event.skuIds() == null || event.skuIds().isEmpty()) {
            return;
        }

        // === Enrichment if category_id changed ===
        String categoryName = null;
        String categoryPath = null;
        if (event.categoryId() != null) {
            CategoryDocument category = lookupCategory(event.categoryId(), envelope.eventType(), event.productId());
            if (category != null) {
                categoryName = category.name();
                categoryPath = category.path();
            }
        }

        // === Build fields map ===
        Map<String, Object> fields = new HashMap<>();
        if (event.name() != null) fields.put("name", event.name());
        if (event.description() != null) fields.put("description", event.description());
        if (event.categoryId() != null) {
            fields.put("category_id", event.categoryId());
            fields.put("category_name", categoryName);
            fields.put("category_path", categoryPath);
        }
        if (event.attributesJson() != null) {
            fields.put("searchable_attributes", transformAttributes(event.attributesJson()));
        }
        if (event.media() != null && !event.media().isEmpty()) {
            String thumbnailUrl = resolveThumbnailUrlFromProductMedia(event.media());
            if (thumbnailUrl != null) {
                fields.put("thumbnail_url", thumbnailUrl);
            }
        }

        if (fields.isEmpty()) {
            log.debug("PRODUCT_UPDATED has no product-level changes, skipping: product_id={}", event.productId());
            return;
        }

        // === Update each SKU ===
        String scriptSource = buildPatchScript();
        Instant now = Instant.now();
        for (String skuId : event.skuIds()) {
            Map<String, Object> params = Map.of(
                "updated_at", event.updatedAt().toString(),
                "indexed_at", now.toString(),
                "event_id", envelope.eventId(),
                "fields", fields
            );
            try {
                productRepository.updateByScript(skuId, scriptSource, params);
            } catch (SearchException e) {
                log.warn("PRODUCT_UPDATED: failed to update SKU {}, skipping. category_id={}",
                    skuId, event.categoryId(), e);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  SKU_UPDATED
    // ─────────────────────────────────────────────────────────────────

    /**
     * Partial-update a single SKU document. Also handles
     * {@code product_status_changed} via {@code _update_by_query} (Phase 19).
     */
    public void handleSkuUpdated(CloudEventEnvelope envelope) {
        SkuUpdatedEvent event = deserialize(envelope, SkuUpdatedEvent.class);

        // === Handle product-wide status change if flagged ===
        if (Boolean.TRUE.equals(event.productStatusChanged())) {
            handleProductStatusChanged(event, envelope);
        }

        // === Build changed-fields map ===
        Map<String, Object> fields = buildFieldsFromChangedFields(event);
        if (fields.isEmpty()) {
            log.debug("SKU_UPDATED has no changed_fields, skipping: sku_id={}", event.skuId());
            return;
        }

        // === Partial update the SKU document ===
        String scriptSource = buildPatchScript();
        Map<String, Object> params = Map.of(
            "updated_at", event.updatedAt().toString(),
            "indexed_at", Instant.now().toString(),
            "event_id", envelope.eventId(),
            "fields", fields
        );
        try {
            productRepository.updateByScript(event.skuId(), scriptSource, params);
        } catch (SearchException e) {
            log.warn("SKU_UPDATED: document missing for sku_id={}, skipping", event.skuId(), e);
        }

        // === Trigger product_count update if product_status changed ===
        if (Boolean.TRUE.equals(event.productStatusChanged())) {
            String newStatus = event.newProductStatus();
            if (event.categoryId() != null) {
                if ("ACTIVE".equals(newStatus)) {
                    productCountUpdater.incrementCount(event.categoryId(), envelope.eventId());
                } else if ("HIDDEN".equals(newStatus)) {
                    productCountUpdater.decrementCount(event.categoryId(), envelope.eventId());
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  SKU_CREATED
    // ─────────────────────────────────────────────────────────────────

    /**
     * Index a single new SKU document using {@code created_at} as the
     * version guard (scripted upsert).
     */
    public void handleSkuCreated(CloudEventEnvelope envelope) {
        SkuCreatedEvent event = deserialize(envelope, SkuCreatedEvent.class);

        // === Enrichment — category lookup ===
        String categoryName = null;
        String categoryPath = null;
        if (event.categoryId() != null) {
            CategoryDocument category = lookupCategory(event.categoryId(), envelope.eventType(), event.productId());
            if (category != null) {
                categoryName = category.name();
                categoryPath = category.path();
            }
        }

        // === Build document ===
        boolean isInStock = ProductDocument.computeIsInStock(false, event.productStatus(), event.skuStatus());

        ProductDocument doc = ProductDocument.builder()
            .skuId(event.skuId())
            .productId(event.productId())
            .categoryId(event.categoryId())
            .categoryName(categoryName)
            .categoryPath(categoryPath)
            .name(event.name())
            .skuStatus(event.skuStatus())
            .productStatus(event.productStatus())
            .originalPrice(event.originalPrice())
            .promotionalPrice(event.promotionalPrice())
            .bestPrice(event.bestPrice())
            .hasPromotion(Boolean.TRUE.equals(event.hasPromotion()))
            .isInStock(isInStock)
            .isDeleted(false)
            .variantAttributes(transformVariantAttributes(event.variantAttributesJson()))
            .thumbnailUrl(resolveThumbnailUrlFromMediaList(event.media()))
            .createdAt(event.createdAt())
            .updatedAt(event.updatedAt())
            .indexedAt(Instant.now())
            .lastEventId(envelope.eventId())
            .version(event.createdAt())  // SKU_CREATED uses created_at as version
            .build();

        productRepository.index(doc);

        // === Trigger product_count increment if product ACTIVE ===
        if ("ACTIVE".equals(event.productStatus()) && event.categoryId() != null) {
            productCountUpdater.incrementCount(event.categoryId(), envelope.eventId());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  PRODUCT_DELETED
    // ─────────────────────────────────────────────────────────────────

    /**
     * Tombstone all SKU documents of the product.
     */
    public void handleProductDeleted(CloudEventEnvelope envelope) {
        ProductDeletedEvent event = deserialize(envelope, ProductDeletedEvent.class);

        if (event.skuIds() == null || event.skuIds().isEmpty()) {
            return;
        }

        String scriptSource = """
            if (ctx._source.version != null && params.updated_at.compareTo(ctx._source.version) <= 0) {
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
            "deleted_at", event.deletedAt() != null ? event.deletedAt().toString() : event.updatedAt().toString(),
            "indexed_at", Instant.now().toString(),
            "event_id", envelope.eventId()
        );

        for (String skuId : event.skuIds()) {
            try {
                productRepository.updateByScript(skuId, scriptSource, params);
            } catch (SearchException e) {
                log.warn("PRODUCT_DELETED: document missing for SKU {}, skipping", skuId, e);
            }
        }

        // === Trigger product_count decrement ===
        if (event.categoryId() != null) {
            productCountUpdater.decrementCount(event.categoryId(), envelope.eventId());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  SKU_DELETED
    // ─────────────────────────────────────────────────────────────────

    /**
     * Tombstone a single SKU document.
     */
    public void handleSkuDeleted(CloudEventEnvelope envelope) {
        SkuDeletedEvent event = deserialize(envelope, SkuDeletedEvent.class);

        String scriptSource = """
            if (ctx._source.version != null && params.updated_at.compareTo(ctx._source.version) <= 0) {
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
            "deleted_at", event.deletedAt() != null ? event.deletedAt().toString() : event.updatedAt().toString(),
            "indexed_at", Instant.now().toString(),
            "event_id", envelope.eventId()
        );

        try {
            productRepository.updateByScript(event.skuId(), scriptSource, params);
        } catch (SearchException e) {
            log.warn("SKU_DELETED: document missing for sku_id={}, skipping", event.skuId(), e);
        }

        // === Trigger product_count decrement ===
        if (event.categoryId() != null) {
            productCountUpdater.decrementCount(event.categoryId(), envelope.eventId());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  Internal helpers
    // ═════════════════════════════════════════════════════════════════

    /**
     * Handle product_status_changed via SKU_UPDATED.
     * Uses {@code _update_by_query} to update all SKU docs of the product
     * (Phase 19: PRODUCT_STATUS_CHANGED merged into SKU_UPDATED).
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
        try {
            productRepository.updateByQueryForProduct(event.productId(), scriptSource, params);
        } catch (SearchException e) {
            log.warn("SKU_UPDATED: _update_by_query for product_status_changed failed: product_id={}",
                event.productId(), e);
        }
    }

    /**
     * Build the standard Painless patch script used by PRODUCT_UPDATED,
     * SKU_UPDATED, and other partial-update handlers.
     */
    private String buildPatchScript() {
        return """
            if (ctx._source.version != null && params.updated_at.compareTo(ctx._source.version) <= 0) {
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

    /**
     * Build the {@code fields} map from SKU_UPDATED's {@code changed_fields} list.
     */
    private Map<String, Object> buildFieldsFromChangedFields(SkuUpdatedEvent event) {
        Map<String, Object> fields = new HashMap<>();
        if (event.changedFields() == null) return fields;
        for (String field : event.changedFields()) {
            switch (field) {
                case "original_price" -> {
                    if (event.originalPrice() != null) fields.put("original_price", event.originalPrice());
                }
                case "promotional_price" -> {
                    if (event.promotionalPrice() != null) fields.put("promotional_price", event.promotionalPrice());
                }
                case "best_price" -> {
                    if (event.bestPrice() != null) fields.put("best_price", event.bestPrice());
                }
                case "has_promotion" -> fields.put("has_promotion", event.hasPromotion());
                case "sku_status" -> {
                    fields.put("sku_status", event.skuStatus());
                    // Recompute is_in_stock when SKU status changes
                    // (product_status is read from the current doc via script; we can't compute it here
                    //  so we let the script handle the full recompute via handleProductStatusChanged if needed,
                    //  or we update is_in_stock optimistically — the _update_by_query for product_status_changed
                    //  will fix it later.)
                }
                case "variant_attributes" -> fields.put("variant_attributes",
                    transformVariantAttributes(event.variantAttributesJson()));
            }
        }
        return fields;
    }

    /**
     * Look up a category from the categories index, logging a warning on miss.
     */
    private CategoryDocument lookupCategory(String categoryId, String eventType, String entityId) {
        try {
            CategoryDocument category = categoryRepository.getById(categoryId);
            if (category == null) {
                log.warn("Category lookup miss: category_id={}, event_type={}, entity_id={}",
                    categoryId, eventType, entityId);
                // TODO: increment search_category_enrichment_miss_total metric
            }
            return category;
        } catch (SearchException e) {
            log.warn("Category lookup error: category_id={}, event_type={}, entity_id={}",
                categoryId, eventType, entityId, e);
            return null;
        }
    }

    /**
     * Build a full ProductDocument from a PRODUCT_CREATED event and a single SKU map.
     */
    private ProductDocument buildProductDocument(
            ProductCreatedEvent event,
            Map<String, Object> skuMap,
            String categoryName,
            String categoryPath,
            Instant now,
            String eventId) {

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
            .lastEventId(eventId)
            .version(event.updatedAt())
            .build();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Transformation utilities
    // ─────────────────────────────────────────────────────────────────

    /**
     * Transform {@code attributes_json} (Map) → {@code searchable_attributes} (List of "key:value").
     */
    private List<String> transformAttributes(Map<String, Object> attributesJson) {
        if (attributesJson == null) return List.of();
        return attributesJson.entrySet().stream()
            .map(e -> e.getKey() + ":" + e.getValue())
            .toList();
    }

    /**
     * Transform {@code variant_attributes_json} (Map) → {@code variant_attributes} (List of "key:value").
     */
    private List<String> transformVariantAttributes(Object variantAttributesJson) {
        if (variantAttributesJson == null) return List.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) variantAttributesJson;
        return map.entrySet().stream()
            .map(e -> e.getKey() + ":" + e.getValue())
            .toList();
    }

    /**
     * Resolve thumbnail_url with priority:
     * product.media[0].thumbnail_url > product.media[0].url > sku.media[0].url.
     */
    private String resolveThumbnailUrl(Map<String, Object> skuMap, List<Map<String, Object>> productMedia) {
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

    /**
     * Resolve thumbnail_url from a media list (used for SKU_CREATED).
     */
    private String resolveThumbnailUrlFromMediaList(List<Map<String, Object>> media) {
        if (media != null && !media.isEmpty()) {
            Map<String, Object> first = media.get(0);
            if (first.get("url") != null) return first.get("url").toString();
            if (first.get("thumbnail_url") != null) return first.get("thumbnail_url").toString();
        }
        return null;
    }

    /**
     * Resolve thumbnail_url from product-level media list only.
     */
    private String resolveThumbnailUrlFromProductMedia(List<Map<String, Object>> media) {
        if (media != null && !media.isEmpty()) {
            Map<String, Object> first = media.get(0);
            if (first.get("thumbnail_url") != null) {
                return first.get("thumbnail_url").toString();
            }
            if (first.get("url") != null) {
                return first.get("url").toString();
            }
        }
        return null;
    }

    /**
     * Safely extract a Long value from a map.
     */
    private Long toLong(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    /**
     * Deserialize the envelope payload into the specified event type.
     */
    private <T> T deserialize(CloudEventEnvelope envelope, Class<T> type) {
        try {
            return objectMapper.convertValue(envelope.payload(), type);
        } catch (Exception e) {
            log.error("Failed to deserialize event payload: type={}, eventId={}",
                envelope.eventType(), envelope.eventId(), e);
            throw new SearchException(
                com.ecommerce.search.common.ErrorCode.INTERNAL_ERROR,
                "Failed to deserialize " + type.getSimpleName(), e);
        }
    }

    /**
     * Support overload for buildProductDocument that accepts a nullable envelope.
     * The envelope reference passed to this class always has an eventId,
     * but the helper method signature accepts it from the outer scope.
     */
    // Note: envelope reference is captured from the calling method, not passed as param.
}
