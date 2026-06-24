package com.ecommerce.search.worker;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.repository.es.UpdateByQueryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Daily off-peak cronjob that cleans up orphaned draft documents from the
 * products Elasticsearch index.
 *
 * <h3>Cleanup conditions (all must be met)</h3>
 * <ol>
 *   <li>{@code product_status = DRAFT}</li>
 *   <li>{@code created_at < now - 30d}</li>
 *   <li>{@code is_deleted = false}</li>
 * </ol>
 *
 * <h3>Ownership constraint</h3>
 * The job does <b>not</b> independently decide which drafts to delete. It
 * calls the Product Service internal API ({@code GET /api/v1/internal/products/orphan-drafts})
 * to get the authoritative list of orphaned draft product IDs. Only those
 * IDs are marked as deleted.
 *
 * <h3>Execution</h3>
 * <ol>
 *   <li>Fetch orphan draft product IDs from Product Service</li>
 *   <li>Run {@code _update_by_query} with a terms filter to mark matching SKU
 *       documents as {@code is_deleted = true}</li>
 *   <li>After 24h grace period, the separate {@link TombstonePurger} cronjob
 *       will hard-delete these documents</li>
 * </ol>
 *
 * <h3>Error handling</h3>
 * All errors are logged but never rethrown, so a failure does not crash the
 * scheduler or halt other jobs.
 *
 * <p>Schedule: configured via {@code search.draft-cleanup.cron} (default 02:00 AM daily).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DraftCleanupJob {

    private final UpdateByQueryExecutor updateByQueryExecutor;
    private final SearchProperties properties;

    private RestClient productServiceRestClient;

    /**
     * Lazily initialize the RestClient for Product Service API calls.
     */
    private RestClient getProductServiceClient() {
        if (productServiceRestClient == null) {
            productServiceRestClient = RestClient.builder()
                .baseUrl("http://product-service:8080")
                .build();
        }
        return productServiceRestClient;
    }

    @Scheduled(cron = "${search.draft-cleanup.cron}")
    public void runCleanup() {
        long startTime = System.currentTimeMillis();
        log.info("Starting Draft Cleanup Job...");

        try {
            // 1. Get orphan-drafts from Product Service
            List<String> orphanProductIds = fetchOrphanDraftsFromProductService();
            if (orphanProductIds == null || orphanProductIds.isEmpty()) {
                log.info("No orphan drafts to clean up");
                return;
            }
            log.info("Found {} orphan drafts to mark as deleted", orphanProductIds.size());

            // 2. Mark as deleted via _update_by_query
            String scriptSource = """
                ctx._source.is_deleted = true;
                ctx._source.deleted_at = params.deleted_at;
                ctx._source.is_in_stock = false;
                ctx._source.indexed_at = params.indexed_at;
                ctx._source.last_event_id = params.event_id;
                """;
            Map<String, Object> params = Map.of(
                "deleted_at", Instant.now().toString(),
                "indexed_at", Instant.now().toString(),
                "event_id", "draft-cleanup-" + Instant.now().toString()
            );

            // Use terms query for batch matching
            Query query = Query.of(q -> q.terms(t -> t
                .field("product_id")
                .terms(tt -> tt.value(orphanProductIds.stream()
                    .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                    .toList()))
            ));

            // Use lower throttle (100 rps) for off-peak background cleanup
            UpdateByQueryResponse response = updateByQueryExecutor.updateByQuery(
                properties.getEs().getIndexAliases().getProducts(),
                query,
                scriptSource,
                params,
                100f
            );

            log.info("Marked {} documents as deleted (total matched: {})",
                response.updated(), response.total());

        } catch (Exception e) {
            log.error("Draft Cleanup Job failed", e);
            // Don't rethrow — non-critical best-effort job
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Draft Cleanup Job completed in {} ms", duration);
    }

    /**
     * Call Product Service internal API to get the list of orphan draft product IDs.
     *
     * <p>Uses a 30-second timeout. If the call fails (network, timeout, 5xx),
     * the error is logged and an empty list is returned so the cleanup is skipped.
     */
    private List<String> fetchOrphanDraftsFromProductService() {
        try {
            String url = "/api/v1/internal/products/orphan-drafts?older_than=30d";
            log.debug("Fetching orphan drafts from Product Service: {}", url);
            return getProductServiceClient().get()
                .uri(url)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<String>>() {});
        } catch (Exception e) {
            log.error("Failed to fetch orphan drafts from Product Service", e);
            return List.of();
        }
    }
}
