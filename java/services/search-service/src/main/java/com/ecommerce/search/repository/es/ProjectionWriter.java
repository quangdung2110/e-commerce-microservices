package com.ecommerce.search.repository.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.InlineScript;
import co.elastic.clients.elasticsearch._types.Script;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.json.JsonData;
import com.ecommerce.search.config.SearchProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * The only place that turns a projection intent into an Elasticsearch write.
 *
 * <p>Every write goes through a canonical painless script so the revision guard runs
 * inside Elasticsearch with no read-modify-write gap ({@code SEARCH-INV-004}), and every
 * write goes through a write alias ({@code SEARCH-INV-007}) — except the catch-up phase of
 * a reindex, which is why the target index is a parameter rather than a constant.
 *
 * <p>Availability and review writes never use {@code scripted_upsert}: a source that does
 * not own the catalog must not be able to create a document ({@code SEARCH-INV-005}).
 */
@Component
public class ProjectionWriter {

    private final ElasticsearchClient client;
    private final PainlessScripts scripts;
    private final SearchProperties properties;

    public ProjectionWriter(ElasticsearchClient client, PainlessScripts scripts, SearchProperties properties) {
        this.client = client;
        this.scripts = scripts;
        this.properties = properties;
    }

    /** Upserts one SKU from a catalog snapshot. Creates the document when absent. */
    public BulkOperation catalogUpsert(String index, String skuId, Map<String, Object> fields,
                                       long catalogRevision, String indexedAt, String eventId) {
        Script script = script(PainlessScripts.CATALOG_UPSERT, Map.of(
                "catalog_revision", JsonData.of(catalogRevision),
                "indexed_at", JsonData.of(indexedAt),
                "event_id", JsonData.of(eventId),
                "fields", JsonData.of(fields)));
        return BulkOperation.of(op -> op.update(update -> update
                .index(index)
                .id(skuId)
                .action(action -> action
                        .script(script)
                        .scriptedUpsert(true)
                        .upsert(Map.of()))));
    }

    /** Tombstones one SKU. Never upserts: a delete must not resurrect a purged document. */
    public BulkOperation catalogTombstone(String index, String skuId, long catalogRevision,
                                          String deletedAt, String indexedAt, String eventId) {
        Script script = script(PainlessScripts.CATALOG_TOMBSTONE, Map.of(
                "catalog_revision", JsonData.of(catalogRevision),
                "deleted_at", JsonData.of(deletedAt),
                "indexed_at", JsonData.of(indexedAt),
                "event_id", JsonData.of(eventId)));
        return BulkOperation.of(op -> op.update(update -> update
                .index(index)
                .id(skuId)
                .action(action -> action.script(script))));
    }

    /**
     * Copies one Inventory-owned availability boolean. Never upserts ({@code SEARCH-BR-010}).
     *
     * <p>Inventory computes the boolean over every warehouse position it owns, so Search
     * takes {@code is_in_stock} as given rather than deriving it from a quantity (ADR-019).
     */
    public BulkOperation availabilityPatch(String index, String skuId, boolean isInStock,
                                           long availabilityRevision, String observedAt, String indexedAt,
                                           String eventId) {
        Script script = script(PainlessScripts.AVAILABILITY_PATCH, Map.of(
                "availability_revision", JsonData.of(availabilityRevision),
                "is_in_stock", JsonData.of(isInStock),
                "observed_at", JsonData.of(observedAt),
                "indexed_at", JsonData.of(indexedAt),
                "event_id", JsonData.of(eventId)));
        return BulkOperation.of(op -> op.update(update -> update
                .index(index)
                .id(skuId)
                .action(action -> action.script(script))));
    }

    /** Upserts one Category node from the Product snapshot. */
    public BulkOperation categoryUpsert(String index, String categoryId, Map<String, Object> fields,
                                        long categoryRevision, String indexedAt, String eventId) {
        Script script = script(PainlessScripts.CATEGORY_UPSERT, Map.of(
                "category_revision", JsonData.of(categoryRevision),
                "indexed_at", JsonData.of(indexedAt),
                "event_id", JsonData.of(eventId),
                "fields", JsonData.of(fields)));
        return BulkOperation.of(op -> op.update(update -> update
                .index(index)
                .id(categoryId)
                .action(action -> action
                        .script(script)
                        .scriptedUpsert(true)
                        .upsert(Map.of()))));
    }

    public BulkOperation categoryTombstone(String index, String categoryId, long categoryRevision,
                                           String deletedAt, String indexedAt, String eventId) {
        Script script = script(PainlessScripts.CATEGORY_TOMBSTONE, Map.of(
                "category_revision", JsonData.of(categoryRevision),
                "deleted_at", JsonData.of(deletedAt),
                "indexed_at", JsonData.of(indexedAt),
                "event_id", JsonData.of(eventId)));
        return BulkOperation.of(op -> op.update(update -> update
                .index(index)
                .id(categoryId)
                .action(action -> action.script(script))));
    }

    /** Writes an absolute recomputed count; the job never increments ({@code ADR-008}). */
    public BulkOperation categoryProductCount(String index, String categoryId, long productCount,
                                              String computedAt) {
        Script script = script(PainlessScripts.CATEGORY_PRODUCT_COUNT, Map.of(
                "product_count", JsonData.of(productCount),
                "computed_at", JsonData.of(computedAt)));
        return BulkOperation.of(op -> op.update(update -> update
                .index(index)
                .id(categoryId)
                .action(action -> action.script(script))));
    }

    /**
     * Applies a Review rating aggregate to every SKU of a product.
     *
     * <p>This is the only {@code _update_by_query} in the write path. It runs with
     * {@code conflicts=proceed} and a throttle: version conflicts from concurrent catalog
     * writes are tolerated because the guard, not the ES version, decides what wins
     * ({@code docs/search/architecture.md §5}).
     */
    public UpdateByQueryResponse reviewMetricsPatch(String index, String productId, double ratingAvg,
                                                    long ratingCount, long reviewRevision, String indexedAt,
                                                    String eventId) throws IOException {
        Script script = script(PainlessScripts.REVIEW_METRICS_PATCH, Map.of(
                "review_revision", JsonData.of(reviewRevision),
                "product_rating_avg", JsonData.of(ratingAvg),
                "product_rating_count", JsonData.of(ratingCount),
                "indexed_at", JsonData.of(indexedAt),
                "event_id", JsonData.of(eventId == null ? "" : eventId)));
        return client.updateByQuery(builder -> builder
                .index(index)
                .query(query -> query.term(term -> term.field("product_id").value(productId)))
                .script(script)
                .conflicts(Conflicts.Proceed)
                .requestsPerSecond(properties.bulk().taskRequestsPerSecond())
                .refresh(false)
                .timeout(timeout -> timeout.time(properties.query().updateByQueryTimeout().toMillis() + "ms")));
    }

    private Script script(String name, Map<String, JsonData> params) {
        return Script.of(builder -> builder.inline(
                InlineScript.of(inline -> inline.source(scripts.source(name)).params(params))));
    }
}
