package com.ecommerce.search.worker.job;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.model.SearchQuery;
import com.ecommerce.search.domain.model.SortOption;
import com.ecommerce.search.repository.es.IndexAdmin;
import com.ecommerce.search.service.ProductQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The checks between catch-up and cutover ({@code WF-SEARCH-009} step 5): the new index must
 * be at least as complete as the one it replaces, and it must answer the queries buyers send.
 *
 * <ul>
 *   <li><b>Count drift.</b> The visible-document count may not move by more than
 *       {@code reindex.max-doc-count-drift-ratio}.
 *   <li><b>Sample diff.</b> {@code 2 x sample-size-per-bucket} SKUs are read from the current
 *       index — the most recently updated and the most reviewed — and fetched from the new
 *       one. A SKU is wrong when it is missing, when any of its three guard revisions is lower
 *       than in the current index, or when the catalog revision is equal but the price or status
 *       differs. A SKU the live indexer changed after catch-up ended is excluded: that is the
 *       tail replay's job, not evidence of a bad build.
 *   <li><b>Smoke queries.</b> The default storefront query, its facet aggregation and every
 *       configured {@code hot-query} must run on the new index and must return hits whenever
 *       the current index does.
 * </ul>
 *
 * <p>Nothing here changes either index.
 */
@Component
public class ReindexVerifier {

    private static final Logger log = LoggerFactory.getLogger(ReindexVerifier.class);

    private final ElasticsearchClient client;
    private final IndexAdmin indexAdmin;
    private final ProductQueryBuilder queryBuilder;
    private final SearchProperties properties;
    private final SearchMetrics metrics;

    public ReindexVerifier(ElasticsearchClient client, IndexAdmin indexAdmin, ProductQueryBuilder queryBuilder,
                           SearchProperties properties, SearchMetrics metrics) {
        this.client = client;
        this.indexAdmin = indexAdmin;
        this.queryBuilder = queryBuilder;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * @param currentProducts   the index the alias serves now, or empty on the very first build
     * @param catchUpEndedAt    when catch-up converged; later changes to the current index are expected
     * @throws IllegalStateException when the new index must not take the aliases
     */
    public void verify(Optional<String> currentProducts, String newProducts, Instant catchUpEndedAt)
            throws IOException {
        if (currentProducts.isEmpty()) {
            log.info("no current products index, nothing to compare the new one with");
            return;
        }
        String current = currentProducts.get();
        countDrift(current, newProducts);
        if (!properties.verify().enabled()) {
            return;
        }
        sampleDiff(current, newProducts, catchUpEndedAt);
        smoke(current, newProducts);
    }

    // ------------------------------------------------------------------ count drift

    private void countDrift(String current, String candidate) throws IOException {
        long before = indexAdmin.countDocuments(current);
        long after = indexAdmin.countDocuments(candidate);
        double drift = before == 0 ? 0 : Math.abs(after - before) / (double) before;
        if (drift > properties.reindex().maxDocCountDriftRatio()) {
            metrics.cutoverCheck("count_drift", "fail");
            throw new IllegalStateException("document count drifted by " + Math.round(drift * 100)
                    + "% (" + before + " -> " + after + "), refusing to cut over");
        }
        metrics.cutoverCheck("count_drift", "ok");
    }

    // ------------------------------------------------------------------ sample diff

    private void sampleDiff(String current, String candidate, Instant catchUpEndedAt) throws IOException {
        int size = properties.verify().sampleSizePerBucket();
        Map<String, ProductDocument> sample = new LinkedHashMap<>();
        sampleBucket(current, "updated_at", size, sample);
        sampleBucket(current, "product_rating_count", size, sample);
        if (sample.isEmpty()) {
            metrics.cutoverCheck("sample_diff", "ok");
            return;
        }

        MgetResponse<ProductDocument> fetched = client.mget(builder -> builder
                .index(candidate)
                .ids(new ArrayList<>(sample.keySet())), ProductDocument.class);

        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (MultiGetResponseItem<ProductDocument> item : fetched.docs()) {
            if (!item.isResult()) {
                continue;
            }
            var result = item.result();
            ProductDocument before = sample.get(result.id());
            if (before == null || changedAfterCatchUp(before, catchUpEndedAt)) {
                continue;
            }
            compared++;
            if (!result.found() || result.source() == null) {
                problems.add(result.id() + " is missing from the new index");
                continue;
            }
            String difference = compare(before, result.source());
            if (difference != null) {
                problems.add(result.id() + " " + difference);
            }
        }
        if (!problems.isEmpty()) {
            metrics.cutoverCheck("sample_diff", "fail");
            throw new IllegalStateException("sample diff found " + problems.size() + " of " + compared
                    + " sampled SKUs wrong, refusing to cut over: " + problems.stream().limit(10).toList());
        }
        log.info("sample diff compared {} SKUs and found no difference", compared);
        metrics.cutoverCheck("sample_diff", "ok");
    }

    /** The most recent or most reviewed non-deleted SKUs of the current index. */
    private void sampleBucket(String index, String sortField, int size, Map<String, ProductDocument> into)
            throws IOException {
        SearchResponse<ProductDocument> response = client.search(builder -> builder
                .index(index)
                .size(size)
                .trackTotalHits(track -> track.enabled(false))
                .query(q -> q.bool(bool -> bool.filter(f -> f.term(t -> t.field("is_deleted").value(false)))))
                .sort(sort -> sort.field(f -> f.field(sortField).order(SortOrder.Desc).missing("_last")))
                .sort(sort -> sort.field(f -> f.field("sku_id").order(SortOrder.Asc))),
                ProductDocument.class);
        response.hits().hits().forEach(hit -> {
            if (hit.source() != null && hit.id() != null) {
                into.putIfAbsent(hit.id(), hit.source());
            }
        });
    }

    private static boolean changedAfterCatchUp(ProductDocument doc, Instant catchUpEndedAt) {
        if (catchUpEndedAt == null) {
            return false;
        }
        return newer(doc.catalogIndexedAt(), catchUpEndedAt)
                || newer(doc.availabilityIndexedAt(), catchUpEndedAt)
                || newer(doc.reviewIndexedAt(), catchUpEndedAt);
    }

    private static boolean newer(Instant value, Instant than) {
        return value != null && value.isAfter(than);
    }

    /** @return a description of the first difference that must not ship, or null */
    static String compare(ProductDocument before, ProductDocument after) {
        if (lower(after.catalogRevision(), before.catalogRevision())) {
            return "has catalog_revision " + after.catalogRevision() + ", the current index has "
                    + before.catalogRevision();
        }
        if (lower(after.availabilityRevision(), before.availabilityRevision())) {
            return "has availability_revision " + after.availabilityRevision() + ", the current index has "
                    + before.availabilityRevision();
        }
        if (lower(after.reviewRevision(), before.reviewRevision())) {
            return "has review_revision " + after.reviewRevision() + ", the current index has "
                    + before.reviewRevision();
        }
        boolean sameCatalog = java.util.Objects.equals(before.catalogRevision(), after.catalogRevision());
        if (sameCatalog && (!java.util.Objects.equals(before.sellingPriceVnd(), after.sellingPriceVnd())
                || !java.util.Objects.equals(before.basePriceVnd(), after.basePriceVnd())
                || !java.util.Objects.equals(before.productStatus(), after.productStatus())
                || !java.util.Objects.equals(before.skuStatus(), after.skuStatus()))) {
            return "differs in price or status at the same catalog_revision " + before.catalogRevision();
        }
        return null;
    }

    private static boolean lower(Long candidate, Long current) {
        return current != null && (candidate == null || candidate < current);
    }

    // ------------------------------------------------------------------ smoke queries

    private void smoke(String current, String candidate) throws IOException {
        List<Check> checks = new ArrayList<>();
        checks.add(new Check("default storefront query", SearchQuery.normalizeQueryText(null)));
        for (String hot : properties.verify().hotQueriesOrEmpty()) {
            checks.add(new Check("hot query '" + hot + "'", SearchQuery.normalizeQueryText(hot)));
        }

        List<String> problems = new ArrayList<>();
        for (Check check : checks) {
            Query query = queryBuilder.build(searchQuery(check.text()), Optional.empty());
            long onCurrent = hits(current, query, false);
            long onCandidate = hits(candidate, query, true);
            if (onCurrent > 0 && onCandidate == 0) {
                problems.add(check.name() + " returns " + onCurrent + " hits on the current index and none on the "
                        + "new one");
            }
        }
        if (!problems.isEmpty()) {
            metrics.cutoverCheck("smoke", "fail");
            throw new IllegalStateException("smoke queries failed, refusing to cut over: " + problems);
        }
        metrics.cutoverCheck("smoke", "ok");
    }

    private record Check(String name, Optional<String> text) {
    }

    private static SearchQuery searchQuery(Optional<String> text) {
        return new SearchQuery(text, Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                false, Optional.empty(), SortOption.RELEVANCE, 1, Optional.empty());
    }

    /** Runs the query, and on the candidate index also its facet aggregation. */
    private long hits(String index, Query query, boolean withFacets) throws IOException {
        SearchResponse<Void> response = client.search(builder -> {
            builder.index(index)
                    .query(query)
                    .size(0)
                    .trackTotalHits(track -> track.enabled(true))
                    .allowPartialSearchResults(false)
                    .timeout(properties.query().productTimeout().toMillis() + "ms");
            if (withFacets) {
                builder.aggregations(queryBuilder.facetAggregations());
            }
            return builder;
        }, Void.class);
        if (Boolean.TRUE.equals(response.timedOut())) {
            throw new IllegalStateException("smoke query timed out on " + index);
        }
        return response.hits().total() == null ? 0L : response.hits().total().value();
    }

    /** Ids of the SKUs a sample would contain; exposed for tests. */
    Set<String> sampleIds(String index, int size) throws IOException {
        Map<String, ProductDocument> sample = new LinkedHashMap<>();
        sampleBucket(index, "updated_at", size, sample);
        sampleBucket(index, "product_rating_count", size, sample);
        return new LinkedHashSet<>(sample.keySet());
    }
}
