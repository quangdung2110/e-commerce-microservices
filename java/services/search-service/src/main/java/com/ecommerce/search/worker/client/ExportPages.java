package com.ecommerce.search.worker.client;

import com.ecommerce.search.domain.event.CatalogPayloads;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Response shapes of the internal export endpoints a full reindex reads.
 *
 * <p>Category, Product and Review carry {@code replay_from_offsets}, captured by the
 * owning service <em>before</em> its scan and repeated unchanged on every page of the
 * session. Search imports the snapshot and then replays each topic from those offsets
 * inclusively; it never reads Kafka itself to capture a catalog watermark (ADR-017).
 * Inventory is the exception: it pins an {@code export_watermark} and Search continues
 * from its change feed instead of a topic offset (ADR-019).
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

    /**
     * {@code POST /exports/inventory/v1/snapshot-sessions}.
     *
     * <p>Inventory has no Kafka replay offset to hand out. It pins one durable
     * {@code export_watermark} instead, and Search resumes from the change feed at that
     * sequence rather than by seeking a topic (ADR-019). The session expires 24 hours
     * after creation.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SnapshotSession(
            String snapshotId,
            Long exportWatermark,
            java.time.Instant createdAt,
            java.time.Instant expiresAt) {
    }

    /**
     * {@code GET /exports/inventory/v1/snapshot-sessions/{snapshot_id}/sku-availabilities};
     * paged by an opaque session-bound cursor.
     *
     * <p>Only SKUs with an active readiness registration appear here, which is what makes
     * a missing target document a contract violation rather than an expected gap.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkuAvailabilitySnapshotPage(
            String snapshotId,
            Long exportWatermark,
            List<CatalogPayloads.SkuAvailability> items,
            String nextCursor) {
    }

    /**
     * {@code GET /exports/inventory/v1/changes}; ordered by export sequence, gaps allowed.
     *
     * <p>{@code items} are the same CloudEvents Inventory publishes to Kafka, so the change
     * feed and the live topic apply through identical code and the same revision guard.
     * A null {@code nextAfterSequence} means the feed is drained.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectionChangePage(
            List<com.ecommerce.search.domain.event.CloudEventEnvelope> items,
            Long minimumRetainedSequence,
            Long latestSequence,
            Long nextAfterSequence) {
    }

    /** {@code GET /internal/v1/reviews/rating-aggregates}; keyset by {@code product_id}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RatingExportPage(
            List<CatalogPayloads.RatingAggregate> items,
            String nextAfterProductId,
            List<ReplayOffset> replayFromOffsets) {
    }
}
