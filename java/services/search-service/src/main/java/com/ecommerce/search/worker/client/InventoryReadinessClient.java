package com.ecommerce.search.worker.client;

import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/**
 * The Inventory readiness handshake, and the one synchronous outbound call Search makes
 * outside a reindex.
 *
 * <p>It sits in the catalog ingestion path on purpose (ADR-019). Inventory must never make
 * an availability fact visible for a SKU whose Search document does not exist yet, and only
 * Search knows when that document has been written — so the catalog consumer registers the
 * SKU here and commits its Kafka offset only afterwards. Registration is what makes
 * Inventory publish the current snapshot even when no stock mutation ever follows.
 *
 * <p>This is not the buyer read path: no API request ever reaches this class.
 */
@Component
public class InventoryReadinessClient {

    /** {@code operation} label of {@code search_inventory_readiness_total}. */
    public static final String REGISTER = "register";
    public static final String REVOKE = "revoke";

    private static final Logger log = LoggerFactory.getLogger(InventoryReadinessClient.class);
    private static final String PATH = "/exports/inventory/v1/search-projection-readiness/{sku_id}";

    private final RestClient http;
    private final SearchProperties properties;
    private final SearchMetrics metrics;

    public InventoryReadinessClient(RestClient.Builder builder, SearchProperties properties,
                                    SearchMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
        this.http = builder.build();
    }

    /**
     * Confirms that the Product-owned document for {@code skuId} exists, at the catalog
     * revision just applied. Idempotent: Inventory keeps the greatest revision.
     */
    public Outcome register(String skuId, long catalogRevision) {
        try {
            http.put()
                    .uri(uri(skuId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("catalog_revision", catalogRevision))
                    .retrieve()
                    .toBodilessEntity();
            metrics.readiness(REGISTER, "ok");
            return Outcome.OK;
        } catch (RestClientResponseException e) {
            return failed(REGISTER, skuId, "status " + e.getStatusCode().value());
        } catch (RestClientException e) {
            return failed(REGISTER, skuId, e.getMessage());
        }
    }

    /**
     * Stops future availability publication for a tombstoned SKU. Must succeed before the
     * document is physically purged ({@code WF-SEARCH-012}).
     *
     * @return {@link Outcome#CONFLICT} when Inventory holds readiness at a newer catalog
     *         revision than the tombstone. Search's own {@code is_deleted} guard rules that
     *         state out, so it is a divergence to investigate, not a call to retry.
     */
    public Outcome revoke(String skuId, long catalogRevision) {
        try {
            http.delete()
                    .uri(uri(skuId, catalogRevision))
                    .retrieve()
                    .toBodilessEntity();
            metrics.readiness(REVOKE, "ok");
            return Outcome.OK;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                log.error("inventory holds readiness for sku {} at a catalog revision newer than the "
                        + "tombstone at {}; search and inventory have diverged", skuId, catalogRevision);
                metrics.readiness(REVOKE, "conflict");
                return Outcome.CONFLICT;
            }
            return failed(REVOKE, skuId, "status " + e.getStatusCode().value());
        } catch (RestClientException e) {
            return failed(REVOKE, skuId, e.getMessage());
        }
    }

    private Outcome failed(String operation, String skuId, String reason) {
        log.warn("inventory readiness {} for sku {} failed, the catalog offset stays uncommitted: {}",
                operation, skuId, reason);
        metrics.readiness(operation, "fail");
        return Outcome.FAILED;
    }

    private URI uri(String skuId) {
        return UriComponentsBuilder.fromUriString(properties.export().inventoryBaseUrl())
                .path(PATH)
                .buildAndExpand(skuId).encode().toUri();
    }

    private URI uri(String skuId, long catalogRevision) {
        return UriComponentsBuilder.fromUriString(properties.export().inventoryBaseUrl())
                .path(PATH)
                .queryParam("catalog_revision", catalogRevision)
                .buildAndExpand(skuId).encode().toUri();
    }

    /** What the caller must do next, which is the only thing an outcome has to express. */
    public enum Outcome {
        /** Registered or revoked; the offset may be committed. */
        OK,
        /** Search and Inventory disagree. Do not retry, do not purge, investigate. */
        CONFLICT,
        /** Leave the offset uncommitted so Kafka redelivers ({@code operations.md §5}). */
        FAILED
    }
}
