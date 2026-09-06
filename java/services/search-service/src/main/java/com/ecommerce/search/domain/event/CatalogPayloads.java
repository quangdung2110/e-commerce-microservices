package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code data} payloads of the Product, Inventory and Review contracts, bound after the
 * consumer has dispatched on the CloudEvents {@code type}.
 *
 * <p>Each record mirrors one JSON Schema under {@code contracts/events/**}. Unknown fields
 * are ignored on purpose: adding an optional field upstream must not break Search.
 */
public final class CatalogPayloads {

    private CatalogPayloads() {
    }

    /** {@code catalog-common.schema.json#/$defs/product}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductSnapshot(
            String productId,
            String categoryId,
            String name,
            String slug,
            String description,
            Map<String, Object> attributes,
            String status,
            Long catalogRevision,
            Instant updatedAt) {
    }

    /** {@code catalog-common.schema.json#/$defs/sku}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkuSnapshot(
            String skuId,
            String skuCode,
            Long basePriceVnd,
            Long sellingPriceVnd,
            String status,
            Map<String, Object> variantAttributes,
            String thumbnailStorageKey,
            Instant updatedAt) {
    }

    /** {@code sku-upserted.schema.json}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkuUpserted(ProductSnapshot product, SkuSnapshot sku, String changeKind) {
    }

    /** {@code sku-deleted.schema.json}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkuDeleted(String productId, String skuId, Long catalogRevision, Instant deletedAt) {
    }

    /** {@code product-deleted.schema.json}; tombstones every SKU listed in {@code skuIds}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductDeleted(String productId, List<String> skuIds, Long catalogRevision, Instant deletedAt) {
    }

    /** One segment of the Category {@code path} Product derives at commit time. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CategoryPathSegment(String categoryId, String name, String slug) {
    }

    /** {@code catalog-common.schema.json#/$defs/category}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CategorySnapshot(
            String categoryId,
            String parentId,
            String name,
            String slug,
            Long categoryRevision,
            List<CategoryPathSegment> path,
            Boolean isLeaf,
            Instant updatedAt) {
    }

    /** {@code category-upserted.schema.json}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CategoryUpserted(CategorySnapshot category, String changeKind) {
    }

    /** {@code category-deleted.schema.json}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CategoryDeleted(String categoryId, Long categoryRevision, Instant deletedAt) {
    }

    /** {@code catalog-reindex-requested.schema.json}; coalesced by root category + revision. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogReindexRequested(
            String reason, String rootCategoryId, Long categoryRevision, Instant requestedAt) {
    }

    /** {@code stock-snapshot.schema.json} and the Inventory export item — same shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StockSnapshot(
            String productId,
            String skuId,
            Long availableQuantity,
            Long stockRevision,
            Instant observedAt) {
    }

    /** {@code review-metrics-updated.schema.json} and the Review export item — same shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RatingAggregate(
            String productId,
            Double averageRating,
            Long totalReviews,
            Long totalWithMedia,
            Long reviewRevision,
            Instant observedAt) {
    }
}
