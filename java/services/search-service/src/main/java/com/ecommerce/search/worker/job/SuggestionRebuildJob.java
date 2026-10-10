package com.ecommerce.search.worker.job;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.document.LeaseLockDocument;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.document.SuggestionDocument;
import com.ecommerce.search.domain.model.SuggestionText;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.repository.SuggestionRepository;
import com.ecommerce.search.repository.es.IndexAdmin;
import com.ecommerce.search.repository.es.IndexTemplates;
import com.ecommerce.search.repository.es.ThrottledBulkWriter;
import com.ecommerce.search.service.ProductQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code WF-SEARCH-010} — rebuilds the suggestion index daily.
 *
 * <p>The build is atomic from the reader's point of view: candidates are scanned from
 * point-in-time views of the two source indices, written into a brand new
 * {@code search_suggestions_v{N+1}}, put through a validation gate, and only then
 * published with a single alias swap. Any failure aborts and leaves the live alias exactly
 * where it was, so the suggest API is never interrupted.
 *
 * <p>Because the source aliases are re-resolved before the swap, a full reindex that cut
 * over mid-build aborts this run rather than publishing terms drawn from the previous
 * catalog generation.
 */
@Component
public class SuggestionRebuildJob {

    private static final Logger log = LoggerFactory.getLogger(SuggestionRebuildJob.class);
    private static final String JOB = "suggestion-rebuild";
    private static final int SCAN_PAGE_SIZE = 1000;

    private final ElasticsearchClient client;
    private final SearchProperties properties;
    private final IndexAdmin indexAdmin;
    private final ThrottledBulkWriter bulkWriter;
    private final JobStateRepository jobState;
    private final SuggestionRepository suggestions;
    private final ProductQueryBuilder queryBuilder;
    private final SearchMetrics metrics;

    public SuggestionRebuildJob(ElasticsearchClient client,
                                SearchProperties properties,
                                IndexAdmin indexAdmin,
                                ThrottledBulkWriter bulkWriter,
                                JobStateRepository jobState,
                                SuggestionRepository suggestions,
                                ProductQueryBuilder queryBuilder,
                                SearchMetrics metrics) {
        this.client = client;
        this.properties = properties;
        this.indexAdmin = indexAdmin;
        this.bulkWriter = bulkWriter;
        this.jobState = jobState;
        this.suggestions = suggestions;
        this.queryBuilder = queryBuilder;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${search.suggestions.rebuild-cron:0 0 3 * * *}")
    public void scheduled() {
        if (!properties.suggestions().rebuildEnabled()) {
            return;
        }
        try {
            run();
        } catch (Exception e) {
            log.error("{} aborted; the current suggestion alias is unchanged: {}", JOB, e.getMessage());
        }
    }

    /** @return number of suggestion documents published, or 0 when the run was skipped */
    public long run() throws Exception {
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Optional<LeaseLockDocument> lease = jobState.acquireLease(
                LeaseLockDocument.SUGGESTION_REBUILD_LOCK, runId, now,
                now.plus(properties.suggestions().leaseDuration()));
        if (lease.isEmpty()) {
            log.info("{} skipped: another replica holds the lease", JOB);
            return 0;
        }

        long startedAt = System.nanoTime();
        String newIndex = null;
        String productsPit = null;
        String categoriesPit = null;
        try {
            String sourceProducts = requireAlias(properties.aliases().products());
            String sourceCategories = requireAlias(properties.aliases().categories());
            productsPit = openPit(sourceProducts);
            categoriesPit = openPit(sourceCategories);

            int version = indexAdmin.nextVersion(IndexTemplates.SEARCH_SUGGESTIONS);
            newIndex = indexAdmin.createVersionedIndex(IndexTemplates.SEARCH_SUGGESTIONS, version);
            saveState(runId, "BUILDING", newIndex, List.of(sourceProducts, sourceCategories), version, null, null);

            Map<String, SuggestionCandidate> deduped = new LinkedHashMap<>();
            scanCategoryCandidates(categoriesPit, deduped);
            scanProductCandidates(productsPit, deduped);

            long published = publish(newIndex, version, deduped, lease.get());
            indexAdmin.refresh(newIndex);

            saveState(runId, "VALIDATING", newIndex, List.of(sourceProducts, sourceCategories), version, null, null);
            validate(newIndex, published);

            // Re-resolve: a full reindex may have cut over while this build was scanning.
            if (!sourceProducts.equals(requireAlias(properties.aliases().products()))
                    || !sourceCategories.equals(requireAlias(properties.aliases().categories()))) {
                throw new IllegalStateException(
                        "source alias moved during the build; refusing to publish terms from the previous catalog");
            }
            if (!jobState.holdsLease(lease.get())) {
                throw new IllegalStateException("lease was lost during the build; another run may already be active");
            }

            Optional<String> rollbackIndex = indexAdmin.resolveAlias(properties.aliases().searchSuggestions());
            indexAdmin.swapAliases(newIndex, List.of(properties.aliases().searchSuggestions()));
            if (rollbackIndex.isPresent()) {
                // The replaced version is the rollback version; the cleanup job times it from here.
                jobState.markIndexRetired(rollbackIndex.get(), runId, Instant.now());
            }
            saveState(runId, "SWAPPED", newIndex, List.of(sourceProducts, sourceCategories), version,
                    rollbackIndex.flatMap(IndexAdmin::versionOf).orElse(null), Map.of("documents", published));

            log.info("{} published {} suggestions into {}", JOB, published, newIndex);
            return published;

        } catch (Exception e) {
            // ABORTED: the new index never carried the alias, so deleting it is safe and
            // the live alias keeps serving the previous generation.
            discard(newIndex);
            saveStateQuietly(runId, "ABORTED", newIndex, List.of(), 0, null,
                    Map.of("error", String.valueOf(e.getMessage())));
            throw e;
        } finally {
            closePit(productsPit);
            closePit(categoriesPit);
            jobState.releaseLease(lease.get());
            metrics.jobDuration(JOB, Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    // ------------------------------------------------------------------ candidates

    /**
     * One candidate per visible category with at least one product. An empty category is
     * skipped so a buyer never picks a phrase that leads to an empty result page.
     */
    private void scanCategoryCandidates(String pit, Map<String, SuggestionCandidate> deduped) throws IOException {
        List<FieldValue> after = null;
        while (true) {
            List<FieldValue> cursor = after;
            SearchResponse<CategoryDocument> response = client.search(builder -> {
                builder.size(SCAN_PAGE_SIZE)
                        .trackTotalHits(track -> track.enabled(false))
                        .pit(p -> p.id(pit).keepAlive(keepAlive()))
                        .query(q -> q.bool(bool -> bool
                                .mustNot(mustNot -> mustNot.term(t -> t.field("is_deleted").value(true)))
                                .filter(filter -> filter.range(range -> range
                                        .field("product_count")
                                        .gt(co.elastic.clients.json.JsonData.of(0))))))
                        .sort(sort -> sort.field(f -> f.field("category_id").order(SortOrder.Asc)));
                if (cursor != null) {
                    builder.searchAfter(cursor);
                }
                return builder;
            }, CategoryDocument.class);

            var hits = response.hits().hits();
            for (var hit : hits) {
                CategoryDocument doc = hit.source();
                if (doc == null || doc.name() == null) {
                    continue;
                }
                accept(deduped, SuggestionCandidate.category(
                        doc.categoryId(), doc.name(), doc.productCountOrZero(), properties.suggestions()));
            }
            if (hits.size() < SCAN_PAGE_SIZE) {
                return;
            }
            after = hits.get(hits.size() - 1).sort();
        }
    }

    /**
     * One candidate per product, not per SKU. The index is SKU-first, so the scan is sorted
     * by {@code product_id} and consecutive SKUs of the same product are folded together:
     * the product is a candidate if any of its SKUs is visible and in stock, and it counts
     * as promoted if any of them is.
     */
    private void scanProductCandidates(String pit, Map<String, SuggestionCandidate> deduped) throws IOException {
        Query visibleAndInStock = Query.of(q -> q.bool(bool -> {
            queryBuilder.visibilityFilters(true).forEach(bool::filter);
            return bool;
        }));

        List<FieldValue> after = null;
        String currentProductId = null;
        String currentName = null;
        Double currentRatingAvg = null;
        Long currentRatingCount = null;
        boolean currentPromotion = false;

        while (true) {
            List<FieldValue> cursor = after;
            SearchResponse<ProductDocument> response = client.search(builder -> {
                builder.size(SCAN_PAGE_SIZE)
                        .trackTotalHits(track -> track.enabled(false))
                        .pit(p -> p.id(pit).keepAlive(keepAlive()))
                        .query(visibleAndInStock)
                        .sort(sort -> sort.field(f -> f.field("product_id").order(SortOrder.Asc)))
                        .sort(sort -> sort.field(f -> f.field("sku_id").order(SortOrder.Asc)));
                if (cursor != null) {
                    builder.searchAfter(cursor);
                }
                return builder;
            }, ProductDocument.class);

            var hits = response.hits().hits();
            for (var hit : hits) {
                ProductDocument doc = hit.source();
                if (doc == null || doc.productId() == null || doc.name() == null || doc.name().isBlank()) {
                    continue;
                }
                if (!doc.productId().equals(currentProductId)) {
                    flushProduct(deduped, currentProductId, currentName, currentRatingAvg,
                            currentRatingCount, currentPromotion);
                    currentProductId = doc.productId();
                    currentName = doc.name();
                    currentRatingAvg = doc.productRatingAvg();
                    currentRatingCount = doc.productRatingCount();
                    currentPromotion = Boolean.TRUE.equals(doc.hasPromotion());
                } else {
                    currentPromotion = currentPromotion || Boolean.TRUE.equals(doc.hasPromotion());
                }
            }
            if (hits.size() < SCAN_PAGE_SIZE) {
                flushProduct(deduped, currentProductId, currentName, currentRatingAvg,
                        currentRatingCount, currentPromotion);
                return;
            }
            after = hits.get(hits.size() - 1).sort();
        }
    }

    private void flushProduct(Map<String, SuggestionCandidate> deduped, String productId, String name,
                              Double ratingAvg, Long ratingCount, boolean hasPromotion) {
        if (productId == null || name == null) {
            return;
        }
        accept(deduped, SuggestionCandidate.product(
                productId, name, ratingAvg, ratingCount, hasPromotion, properties.suggestions()));
    }

    /** Drops phrases that normalise to nothing, then keeps the best of each dedupe group. */
    private void accept(Map<String, SuggestionCandidate> deduped, SuggestionCandidate candidate) {
        if (candidate.textNormalized().isEmpty()) {
            return;
        }
        String key = candidate.dedupeKey(properties.suggestions().scope(), properties.suggestions().language());
        deduped.merge(key, candidate, SuggestionCandidate::betterOf);
    }

    // ------------------------------------------------------------------ publish and validate

    private long publish(String index, int version, Map<String, SuggestionCandidate> deduped,
                         LeaseLockDocument lease) throws Exception {
        SearchProperties.Suggestions config = properties.suggestions();
        Instant updatedAt = Instant.now();
        Instant leaseCheckpoint = Instant.now();
        long published = 0;

        try (ThrottledBulkWriter.Session session =
                     bulkWriter.open(JOB, Instant.now().plus(Duration.ofHours(2)))) {
            for (SuggestionCandidate candidate : deduped.values()) {
                String id = SuggestionText.suggestionId(candidate.type(), config.scope(),
                        config.language(), candidate.textNormalized());
                SuggestionDocument document = new SuggestionDocument(
                        id,
                        candidate.text(),
                        candidate.textNormalized(),
                        candidate.type(),
                        candidate.type().equals(SuggestionCandidate.TYPE_CATEGORY)
                                ? "CATEGORY_NAME" : "PRODUCT_NAME",
                        config.scope(),
                        config.language(),
                        candidate.type().equals(SuggestionCandidate.TYPE_CATEGORY) ? candidate.sourceId() : null,
                        candidate.type().equals(SuggestionCandidate.TYPE_PRODUCT) ? candidate.sourceId() : null,
                        candidate.weight(),
                        candidate.ratingAvg(),
                        candidate.ratingCount(),
                        candidate.productCount(),
                        (long) version,
                        updatedAt);

                session.add(BulkOperation.of(op -> op.index(idx -> idx
                        .index(index).id(id).document(document))), 512);
                published++;

                // Renewing while the bulk stream is running keeps a long build from losing
                // the lease it will need to check again before the swap.
                if (Duration.between(leaseCheckpoint, Instant.now())
                        .compareTo(properties.suggestions().leaseRenewInterval()) > 0) {
                    leaseCheckpoint = Instant.now();
                    if (!jobState.renewLease(lease, leaseCheckpoint,
                            leaseCheckpoint.plus(properties.suggestions().leaseDuration()))) {
                        throw new IllegalStateException("could not renew the suggestion rebuild lease");
                    }
                }
            }
            session.flush();
            if (!session.failures().isEmpty()) {
                throw new IllegalStateException("bulk item failures during the build: " + session.failures());
            }
        }
        return published;
    }

    /**
     * The validation gate. Everything is checked against the new physical index, before it
     * has an alias, so a bad build is never visible to a buyer.
     */
    private void validate(String index, long published) throws IOException {
        SearchProperties.Suggestions config = properties.suggestions();

        long actual = indexAdmin.countDocuments(index);
        if (actual < config.minDocs()) {
            throw new IllegalStateException("only " + actual + " suggestions built, below the minimum "
                    + config.minDocs());
        }
        if (actual != published) {
            throw new IllegalStateException("expected " + published + " unique ids but the index holds " + actual);
        }

        Optional<String> currentIndex = indexAdmin.resolveAlias(properties.aliases().searchSuggestions());
        if (currentIndex.isPresent()) {
            long previous = indexAdmin.countDocuments(currentIndex.get());
            if (previous > 0 && actual < previous * config.minRatioToPreviousVersion()) {
                throw new IllegalStateException("document count fell from " + previous + " to " + actual
                        + ", below the " + config.minRatioToPreviousVersion() + " ratio gate");
            }
        }

        for (String prefix : config.hotPrefixes() == null ? List.<String>of() : config.hotPrefixes()) {
            if (suggestions.suggestIn(index, prefix, 1).isEmpty()) {
                throw new IllegalStateException("hot prefix '" + prefix + "' returned no suggestion");
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    private String requireAlias(String alias) throws IOException {
        return indexAdmin.resolveAlias(alias).orElseThrow(() ->
                new IllegalStateException("alias " + alias + " does not resolve to an index"));
    }

    private String openPit(String index) throws IOException {
        OpenPointInTimeResponse response = client.openPointInTime(builder -> builder
                .index(index).keepAlive(keepAlive()));
        return response.id();
    }

    private void closePit(String pit) {
        if (pit == null) {
            return;
        }
        try {
            client.closePointInTime(builder -> builder.id(pit));
        } catch (IOException e) {
            log.warn("could not close point-in-time {}: {}", pit, e.getMessage());
        }
    }

    private co.elastic.clients.elasticsearch._types.Time keepAlive() {
        return co.elastic.clients.elasticsearch._types.Time.of(t ->
                t.time(properties.suggestions().pitKeepAlive().toMillis() + "ms"));
    }

    private void discard(String index) {
        if (index == null) {
            return;
        }
        try {
            indexAdmin.deleteIndex(index);
        } catch (IOException e) {
            log.warn("could not delete aborted suggestion index {}: {}", index, e.getMessage());
        }
    }

    private void saveState(String runId, String phase, String index, List<String> sources, int version,
                           Integer rollbackVersion, Map<String, Object> validation) throws IOException {
        Map<String, Object> state = new HashMap<>();
        state.put("doc_kind", "SUGGESTION_BUILD");
        state.put("run_id", runId);
        state.put("phase", phase);
        state.put("status", phase);
        state.put("target_index", index);
        state.put("source_indices", new ArrayList<>(sources));
        state.put("current_version", (long) version);
        state.put("rollback_version", rollbackVersion == null ? null : rollbackVersion.longValue());
        state.put("validation", validation);
        state.put("started_at", Instant.now());
        state.put("updated_at", Instant.now());
        jobState.saveSuggestionBuild(runId, state);
    }

    private void saveStateQuietly(String runId, String phase, String index, List<String> sources, int version,
                                  Integer rollbackVersion, Map<String, Object> validation) {
        try {
            saveState(runId, phase, index, sources, version, rollbackVersion, validation);
        } catch (IOException e) {
            log.error("could not persist suggestion build state for {}: {}", runId, e.getMessage());
        }
    }
}
