package com.ecommerce.search.worker.client;

import com.ecommerce.search.config.SearchProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * The only outbound calls Search makes, and only during a full reindex.
 *
 * <p>Nothing here may be called from the read path or from event processing: a synchronous
 * hop into Product or Inventory would push buyer read traffic back onto a write store
 * ({@code docs/search/architecture.md §7}).
 */
@Component
public class SnapshotExportClient {

    private final RestClient http;
    private final SearchProperties properties;

    public SnapshotExportClient(RestClient.Builder builder, SearchProperties properties) {
        this.properties = properties;
        this.http = builder.build();
    }

    /** Category export; Search reads it before the Product export (ADR-013). */
    public ExportPages.CategoryExportPage categories(String cursor) {
        URI uri = UriComponentsBuilder.fromUriString(properties.export().productBaseUrl())
                .path("/internal/v1/categories/export")
                .queryParam("limit", properties.reindex().exportPageSize())
                .queryParamIfPresent("cursor", java.util.Optional.ofNullable(cursor))
                .build(true).toUri();
        return http.get().uri(uri).retrieve().body(ExportPages.CategoryExportPage.class);
    }

    public ExportPages.ProductExportPage products(String cursor) {
        URI uri = UriComponentsBuilder.fromUriString(properties.export().productBaseUrl())
                .path("/internal/v1/products/export")
                .queryParam("limit", properties.reindex().exportPageSize())
                .queryParamIfPresent("cursor", java.util.Optional.ofNullable(cursor))
                .build(true).toUri();
        return http.get().uri(uri).retrieve().body(ExportPages.ProductExportPage.class);
    }

    /** Pins one Inventory availability watermark; the session expires 24 hours later. */
    public ExportPages.SnapshotSession createInventorySnapshotSession() {
        URI uri = UriComponentsBuilder.fromUriString(properties.export().inventoryBaseUrl())
                .path("/exports/inventory/v1/snapshot-sessions")
                .build(true).toUri();
        return http.post().uri(uri).retrieve().body(ExportPages.SnapshotSession.class);
    }

    /** Pages the per-SKU availability fixed at the session's watermark. */
    public ExportPages.SkuAvailabilitySnapshotPage skuAvailabilities(String snapshotId, String cursor) {
        URI uri = UriComponentsBuilder.fromUriString(properties.export().inventoryBaseUrl())
                .path("/exports/inventory/v1/snapshot-sessions/{snapshot_id}/sku-availabilities")
                .queryParam("limit", properties.reindex().exportPageSize())
                .queryParamIfPresent("cursor", java.util.Optional.ofNullable(cursor))
                .buildAndExpand(snapshotId).encode().toUri();
        return http.get().uri(uri).retrieve().body(ExportPages.SkuAvailabilitySnapshotPage.class);
    }

    public ExportPages.RatingExportPage ratingAggregates(String afterProductId) {
        URI uri = UriComponentsBuilder.fromUriString(properties.export().reviewBaseUrl())
                .path("/internal/v1/reviews/rating-aggregates")
                .queryParam("limit", properties.reindex().exportPageSize())
                .queryParamIfPresent("after_product_id", java.util.Optional.ofNullable(afterProductId))
                .build(true).toUri();
        return http.get().uri(uri).retrieve().body(ExportPages.RatingExportPage.class);
    }
}
