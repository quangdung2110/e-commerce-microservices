package com.ecommerce.search.worker.client;

import com.ecommerce.search.domain.event.CatalogPayloads;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Response shapes of the four internal export endpoints a full reindex reads.
 *
 * <p>All four carry {@code replay_from_offsets}, captured by the owning service
 * <em>before</em> its scan and repeated unchanged on every page of the session. Search
 * imports the snapshot and then replays each topic from those offsets inclusively; it
 * never reads Kafka itself to capture a catalog watermark (ADR-017).
 */
public final class ExportPages {

    private ExportPages() {
    }

    /** One Kafka position a source tells Search to resume from. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReplayOffset(String topic, int partition, long offset) {
    }

    /** {@code GET /internal/v1/categories/export}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CategoryExportPage(
            List<CatalogPayloads.CategorySnapshot> categories,
            String nextCursor,
            List<ReplayOffset> replayFromOffsets) {
    }

    /** A SKU as it appears inside an exported Product. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportedSku(
            String skuId,
            String productId,
            String skuCode,
            Long basePriceVnd,
            Long sellingPriceVnd,
            String status,
            Map<String, Object> variantAttributes,
            String thumbnailStorageKey,
            java.time.Instant updatedAt) {

        /** Adapts to the shape the shared catalog projection expects. */
        public CatalogPayloads.SkuSnapshot toSnapshot() {
            return new CatalogPayloads.SkuSnapshot(
                    skuId, skuCode, basePriceVnd, sellingPriceVnd, status,
                    variantAttributes, thumbnailStorageKey, updatedAt);
        }
    }

    /** {@code GET /internal/v1/products/export}; one item carries a product and its SKUs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportedProduct(
            String productId,
            String categoryId,
            String name,
            String slug,
            String description,
            Map<String, Object> attributes,
            String status,
            Long catalogRevision,
            List<ExportedSku> skus,
            java.time.Instant updatedAt) {

        public CatalogPayloads.ProductSnapshot toSnapshot() {
            return new CatalogPayloads.ProductSnapshot(
                    productId, categoryId, name, slug, description, attributes,
                    status, catalogRevision, updatedAt);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductExportPage(
            List<ExportedProduct> products,
            String nextCursor,
            List<ReplayOffset> replayFromOffsets) {
    }

    /** {@code GET /internal/v1/inventory/stock-snapshots}; keyset by {@code sku_id}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StockExportPage(
            List<CatalogPayloads.StockSnapshot> items,
            String nextAfterSkuId,
            List<ReplayOffset> replayFromOffsets) {
    }

    /** {@code GET /internal/v1/reviews/rating-aggregates}; keyset by {@code product_id}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RatingExportPage(
            List<CatalogPayloads.RatingAggregate> items,
            String nextAfterProductId,
            List<ReplayOffset> replayFromOffsets) {
    }
}
