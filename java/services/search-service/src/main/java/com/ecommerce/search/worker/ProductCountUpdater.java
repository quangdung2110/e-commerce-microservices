package com.ecommerce.search.worker;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.repository.es.UpdateByQueryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Realtime updater for {@code categories.product_count} on the categories
 * Elasticsearch index.
 *
 * <p>Uses {@code _update_by_query} with Painless arithmetic scripts to
 * increment or decrement {@code product_count} when product visibility
 * changes. The count is approximate (accepts ±1-2 drift due to Kafka
 * redelivery); a Full Reindex Job can reconcile when needed.
 *
 * <p>Triggered by {@link handler.ProductEventHandlers} when processing:
 * <ul>
 *   <li>{@code PRODUCT_CREATED} (status = ACTIVE) → increment</li>
 *   <li>{@code SKU_UPDATED.product_status_changed = true → ACTIVE} → increment</li>
 *   <li>{@code SKU_UPDATED.product_status_changed = true → HIDDEN} → decrement</li>
 *   <li>{@code SKU_CREATED} (product ACTIVE) → increment</li>
 *   <li>{@code PRODUCT_DELETED} → decrement</li>
 *   <li>{@code SKU_DELETED} → decrement</li>
 * </ul>
 *
 * <h3>Error handling (Phase 22b — No DLQ)</h3>
 * Failures are logged and silently swallowed. Never rethrown — this is a
 * non-critical, best-effort update.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCountUpdater {

    private final UpdateByQueryExecutor updateByQueryExecutor;
    private final SearchProperties properties;

    /**
     * Increment {@code product_count} by 1 for the given category.
     *
     * <p>Called when a product becomes visible in search results:
     * {@code PRODUCT_CREATED} (ACTIVE) or {@code SKU_UPDATED.product_status_changed → ACTIVE}.
     *
     * @param categoryId the category whose count should be increased
     * @param eventId    the event that triggered this update (for audit trail)
     */
    public void incrementCount(String categoryId, String eventId) {
        try {
            String scriptSource = """
                if (ctx._source.product_count == null) {
                  ctx._source.product_count = 0;
                }
                ctx._source.product_count += 1;
                ctx._source.indexed_at = params.indexed_at;
                ctx._source.last_event_id = params.event_id;
                """;
            execute(categoryId, eventId, scriptSource);
            log.debug("Incremented product_count for category_id={}", categoryId);
        } catch (Exception e) {
            log.error("Failed to increment product_count for category_id={}", categoryId, e);
            // Don't rethrow — non-recoverable error (Phase 22b)
        }
    }

    /**
     * Decrement {@code product_count} by 1 for the given category.
     *
     * <p>Called when a product is removed from search results:
     * {@code SKU_UPDATED.product_status_changed → HIDDEN}, {@code PRODUCT_DELETED},
     * or {@code SKU_DELETED}.
     *
     * <p>Includes a {@code product_count > 0} guard to prevent the counter
     * from going negative.
     *
     * @param categoryId the category whose count should be decreased
     * @param eventId    the event that triggered this update (for audit trail)
     */
    public void decrementCount(String categoryId, String eventId) {
        try {
            String scriptSource = """
                if (ctx._source.product_count == null) {
                  ctx._source.product_count = 0;
                }
                if (ctx._source.product_count > 0) {
                  ctx._source.product_count -= 1;
                }
                ctx._source.indexed_at = params.indexed_at;
                ctx._source.last_event_id = params.event_id;
                """;
            execute(categoryId, eventId, scriptSource);
            log.debug("Decremented product_count for category_id={}", categoryId);
        } catch (Exception e) {
            log.error("Failed to decrement product_count for category_id={}", categoryId, e);
            // Don't rethrow — non-recoverable error (Phase 22b)
        }
    }

    /**
     * Common execution path: build a term query on {@code category_id}, set up
     * the Painless script parameters, and delegate to {@link UpdateByQueryExecutor}.
     */
    private void execute(String categoryId, String eventId, String scriptSource) {
        Query query = Query.of(q -> q.term(t -> t.field("category_id").value(categoryId)));
        Map<String, Object> params = Map.of(
            "indexed_at", Instant.now().toString(),
            "event_id", eventId
        );
        UpdateByQueryResponse response = updateByQueryExecutor.updateByQuery(
            properties.getEs().getIndexAliases().getCategories(),
            query,
            scriptSource,
            params
        );
        log.debug("product_count _update_by_query: category_id={}, updated={}, total={}",
            categoryId, response.updated(), response.total());
    }
}
