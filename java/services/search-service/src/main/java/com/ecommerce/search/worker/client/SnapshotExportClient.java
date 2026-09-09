package com.ecommerce.search.worker.client;

import com.ecommerce.search.config.SearchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Optional;

/**
 * The bootstrap and recovery reads of a full reindex.
 *
 * <p>Nothing here may be called from the read path: a synchronous hop into Product or
 * Inventory would push buyer read traffic back onto a write store
 * ({@code docs/search/architecture.md §7}). The readiness handshake in
 * {@link InventoryReadinessClient} is the one outbound call that does run per event, and it
 * is a deliberate exception the design names (ADR-019).
 */
@Component
public class SnapshotExportClient {

    private final RestClient http;
    private final SearchProperties properties;
    private final ObjectMapper objectMapper;

    public SnapshotExportClient(RestClient.Builder builder, SearchProperties properties,
                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
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

    /**
     * Pages the per-SKU availability fixed at the session's watermark.
     *
     * @throws InventoryExportException when the session has expired, which a {@code 404}
     *         also means: the session is gone either way, and the watermark with it
     */
    public ExportPages.SkuAvailabilitySnapshotPage skuAvailabilities(String snapshotId, String cursor) {
        URI uri = UriComponentsBuilder.fromUriString(properties.export().inventoryBaseUrl())
                .path("/exports/inventory/v1/snapshot-sessions/{snapshot_id}/sku-availabilities")
                .queryParam("limit", properties.reindex().exportPageSize())
                .queryParamIfPresent("cursor", java.util.Optional.ofNullable(cursor))
                .buildAndExpand(snapshotId).encode().toUri();
        try {
            return http.get().uri(uri).retrieve().body(ExportPages.SkuAvailabilitySnapshotPage.class);
        } catch (RestClientResponseException e) {
            throw translate(e, InventoryExportException.Reason.SNAPSHOT_SESSION_EXPIRED,
                    "snapshot session " + snapshotId);
        }
    }

    /**
     * Reads ordered availability changes after an export sequence.
     *
     * @throws InventoryExportException when the cursor has fallen out of the retention
     *         window and the whole projection has to be bootstrapped again
     */
    public ExportPages.ProjectionChangePage changes(long afterSequence) {
        URI uri = UriComponentsBuilder.fromUriString(properties.export().inventoryBaseUrl())
                .path("/exports/inventory/v1/changes")
                .queryParam("after_sequence", afterSequence)
                .queryParam("limit", properties.reindex().exportPageSize())
                .build(true).toUri();
        try {
            return http.get().uri(uri).retrieve().body(ExportPages.ProjectionChangePage.class);
        } catch (RestClientResponseException e) {
            throw translate(e, InventoryExportException.Reason.REPLAY_WINDOW_EXPIRED,
                    "change feed after sequence " + afterSequence);
        }
    }

    /**
     * Turns a {@code 410} or {@code 404} into the recovery it calls for.
     *
     * <p>The problem body names the code, and it is trusted when present. Both endpoints
     * answer {@code 410} though, so when the body says nothing usable the endpoint the call
     * was made against decides — that is the only thing left that distinguishes them.
     */
    private InventoryExportException translate(RestClientResponseException e,
                                               InventoryExportException.Reason endpointDefault,
                                               String what) {
        int status = e.getStatusCode().value();
        if (status != 410 && status != 404) {
            throw e;
        }
        InventoryExportException.Reason reason = declaredReason(e).orElse(
                status == 404 ? InventoryExportException.Reason.SNAPSHOT_SESSION_EXPIRED : endpointDefault);
        return new InventoryExportException(reason, what + " is gone: " + reason.name());
    }

    private Optional<InventoryExportException.Reason> declaredReason(RestClientResponseException e) {
        try {
            JsonNode problem = objectMapper.readTree(e.getResponseBodyAsString());
            String code = problem.path("code").asText(null);
            for (InventoryExportException.Reason reason : InventoryExportException.Reason.values()) {
                if (reason.name().equals(code)) {
                    return Optional.of(reason);
                }
            }
        } catch (Exception ignored) {
            // An unparsable or empty problem body is not itself an error; the endpoint the
            // call was made against still identifies the recovery.
        }
        return Optional.empty();
    }

    /**
     * Opens a Review rating export session, pinning the {@code t.review.events} offsets to
     * replay from before any aggregate is scanned.
     *
     * <p>Creating the session writes durable state on Review's side, which is why it is a
     * {@code POST} and not a parameterless {@code GET} (ADR-016).
     */
    public ExportPages.RatingExportSession createRatingExportSession() {
        URI uri = UriComponentsBuilder.fromUriString(properties.export().reviewBaseUrl())
                .path("/internal/v1/reviews/rating-aggregate-exports")
                .build(true).toUri();
        return http.post().uri(uri).retrieve().body(ExportPages.RatingExportSession.class);
    }

    /**
     * Pages the aggregates keyset-ordered by {@code product_id} within one session.
     *
     * @throws RatingExportSessionException when the session has expired or is unknown, which
     *         invalidates the offsets every page so far was imported against
     */
    public ExportPages.RatingExportPage ratingAggregates(String sessionId, String afterProductId) {
        URI uri = UriComponentsBuilder.fromUriString(properties.export().reviewBaseUrl())
                .path("/internal/v1/reviews/rating-aggregate-exports/{session_id}")
                .queryParam("limit", properties.reindex().exportPageSize())
                .queryParamIfPresent("after_product_id", java.util.Optional.ofNullable(afterProductId))
                .buildAndExpand(sessionId).encode().toUri();
        try {
            return http.get().uri(uri).retrieve().body(ExportPages.RatingExportPage.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status != 410 && status != 404) {
                throw e;
            }
            throw new RatingExportSessionException(
                    "rating export session " + sessionId + " is gone: HTTP " + status);
        }
    }
}
