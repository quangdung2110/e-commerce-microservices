package com.ecommerce.search.worker.job;

import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.document.ReindexRunDocument;
import com.ecommerce.search.domain.event.CatalogPayloads;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.repository.es.IndexAdmin;
import com.ecommerce.search.repository.es.IndexTemplates;
import com.ecommerce.search.repository.es.ProjectionWriter;
import com.ecommerce.search.repository.es.ThrottledBulkWriter;
import com.ecommerce.search.worker.ReindexControlCoordinator;
import com.ecommerce.search.worker.client.ExportPages;
import com.ecommerce.search.worker.client.SnapshotExportClient;
import com.ecommerce.search.worker.handler.CatalogProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code WF-SEARCH-009} — the three-phase full reindex.
 *
 * <p>Build imports every snapshot into a new versioned index; Catch-up replays each topic
 * from the offsets the owning services captured, writing <em>directly</em> into that index
 * rather than through the write alias; Cutover moves both aliases in a single atomic
 * {@code _aliases} call. Rollback is the reverse swap (ADR-006).
 *
 * <p>The run fails rather than cuts over whenever a snapshot export fails, an export omits
 * its {@code replay_from_offsets}, catch-up does not converge, or a control event
 * supersedes the run.
 */
@Component
public class FullReindexJob {

    private static final Logger log = LoggerFactory.getLogger(FullReindexJob.class);

    private static final String SOURCE_CATEGORY = "category";
    private static final String SOURCE_PRODUCT = "product";
    private static final String SOURCE_AVAILABILITY = "inventory_availability";
    private static final String SOURCE_REVIEW = "review";

    private final SearchProperties properties;
    private final SnapshotExportClient exports;
    private final IndexAdmin indexAdmin;
    private final ProjectionWriter writer;
    private final ThrottledBulkWriter bulkWriter;
    private final JobStateRepository runs;
    private final ReindexCatchUp catchUp;
    private final ReindexControlCoordinator control;
    private final SearchMetrics metrics;

    public FullReindexJob(SearchProperties properties,
                          SnapshotExportClient exports,
                          IndexAdmin indexAdmin,
                          ProjectionWriter writer,
                          ThrottledBulkWriter bulkWriter,
                          JobStateRepository runs,
                          ReindexCatchUp catchUp,
                          ReindexControlCoordinator control,
                          SearchMetrics metrics) {
        this.properties = properties;
        this.exports = exports;
        this.indexAdmin = indexAdmin;
        this.writer = writer;
        this.bulkWriter = bulkWriter;
        this.runs = runs;
        this.catchUp = catchUp;
        this.control = control;
        this.metrics = metrics;
    }

    /** Runs the whole three-phase pipeline off the request thread. */
    @Async("reindexExecutor")
    public void run(String runId, String reason, String actor) {
        Instant startedAt = Instant.now();
        Instant deadline = startedAt.plus(properties.reindex().runDeadline());
        ReindexProgressState progress = new ReindexProgressState();
        control.consumePending();

        String productsIndex = null;
        String categoriesIndex = null;
        try {
            categoriesIndex = indexAdmin.createVersionedIndex(
                    IndexTemplates.CATEGORIES, indexAdmin.nextVersion(IndexTemplates.CATEGORIES));
            productsIndex = indexAdmin.createVersionedIndex(
                    IndexTemplates.PRODUCTS, indexAdmin.nextVersion(IndexTemplates.PRODUCTS));

            save(runId, "BUILDING", "BUILDING", reason, actor, startedAt, null, progress, productsIndex, null);
            metrics.reindexState("BUILDING", 1);

            buildCategories(categoriesIndex, progress, deadline);
            buildCatalog(productsIndex, categoriesIndex, progress, deadline);
            buildAvailability(productsIndex, progress, deadline);
            buildRating(productsIndex, progress, deadline);

            requireOffsets(progress);
            failIfSuperseded(runId, productsIndex, categoriesIndex, reason, actor, startedAt, progress);

            save(runId, "CATCHING_UP", "CATCHING_UP", reason, actor, startedAt, null,
                    progress, productsIndex, null);
            metrics.reindexState("CATCHING_UP", 1);
            catchUp.replay(runId, productsIndex, categoriesIndex, progress, deadline);

            failIfSuperseded(runId, productsIndex, categoriesIndex, reason, actor, startedAt, progress);

            save(runId, "CUTOVER", "CUTOVER", reason, actor, startedAt, null, progress, productsIndex, null);
            metrics.reindexState("CUTOVER", 1);
            cutover(productsIndex, categoriesIndex);

            save(runId, "COMPLETED", null, reason, actor, startedAt, Instant.now(),
                    progress, productsIndex, null);
            metrics.reindexState("COMPLETED", 1);
            log.info("reindex run {} completed; aliases now serve {} and {}", runId, productsIndex, categoriesIndex);

        } catch (Exception e) {
            log.error("reindex run {} failed: {}", runId, e.getMessage(), e);
            discard(productsIndex);
            discard(categoriesIndex);
            saveQuietly(runId, "FAILED", null, reason, actor, startedAt, Instant.now(), progress, productsIndex,
                    new String[]{"REINDEX_FAILED", String.valueOf(e.getMessage())});
            metrics.reindexState("FAILED", 1);
        }
    }

    // ------------------------------------------------------------------ build phases

    /** Category export runs first so the Product build can enrich from a fresh tree. */
    private void buildCategories(String categoriesIndex, ReindexProgressState progress, Instant deadline)
            throws IOException {
        String cursor = null;
        try (ThrottledBulkWriter.Session session = bulkWriter.open("reindex-categories", deadline)) {
            do {
                ExportPages.CategoryExportPage page = exports.categories(cursor);
                progress.recordOffsets(SOURCE_CATEGORY, page.replayFromOffsets());
                for (CatalogPayloads.CategorySnapshot category : nullSafe(page.categories())) {
                    if (!CatalogProjection.hasValidPath(category)) {
                        log.warn("skipping exported category {} with an unusable path", category.categoryId());
                        continue;
                    }
                    Map<String, Object> fields = CatalogProjection.toCategoryFields(category);
                    session.add(writer.categoryUpsert(categoriesIndex, category.categoryId(), fields,
                                    category.categoryRevision(), Timestamps.nowIso(), "reindex"),
                            estimateBytes(fields));
                }
                progress.addCategories(nullSafe(page.categories()).size());
                cursor = page.nextCursor();
            } while (cursor != null && !cursor.isBlank());
        }
        indexAdmin.refresh(categoriesIndex);
        metrics.snapshotExport(SOURCE_CATEGORY, "success");
    }

    /**
     * Product build. Category enrichment reads the freshly built category index directly,
     * not the alias, so the new SKU documents carry the same tree the run exported.
     */
    private void buildCatalog(String productsIndex, String categoriesIndex,
                              ReindexProgressState progress, Instant deadline) throws IOException {
        Map<String, CategoryDocument> categoryCache = new HashMap<>();
        String cursor = null;
        try (ThrottledBulkWriter.Session session = bulkWriter.open("reindex-catalog", deadline)) {
            do {
                ExportPages.ProductExportPage page = exports.products(cursor);
                progress.recordOffsets(SOURCE_PRODUCT, page.replayFromOffsets());
                for (ExportPages.ExportedProduct product : nullSafe(page.products())) {
                    Optional<CategoryDocument> category =
                            lookupCategory(categoriesIndex, categoryCache, product.categoryId());
                    for (ExportPages.ExportedSku sku : nullSafe(product.skus())) {
                        Map<String, Object> fields = CatalogProjection.toFields(
                                product.toSnapshot(), sku.toSnapshot(), category);
                        session.add(writer.catalogUpsert(productsIndex, sku.skuId(), fields,
                                        product.catalogRevision(), Timestamps.nowIso(), "reindex"),
                                estimateBytes(fields));
                    }
                }
                progress.addProducts(nullSafe(page.products()).size());
                cursor = page.nextCursor();
            } while (cursor != null && !cursor.isBlank());
        }
        indexAdmin.refresh(productsIndex);
        metrics.snapshotExport(SOURCE_PRODUCT, "success");
    }

    /**
     * Availability build, after the catalog so the target documents exist. Inventory pins
     * one {@code export_watermark} for the whole session; the import never upserts, so a
     * SKU created after the Product snapshot has no document here yet (ADR-019).
     */
    private void buildAvailability(String productsIndex, ReindexProgressState progress, Instant deadline)
            throws IOException {
        ExportPages.SnapshotSession snapshot = exports.createInventorySnapshotSession();
        if (snapshot == null || snapshot.snapshotId() == null || snapshot.exportWatermark() == null) {
            throw new IllegalStateException("inventory snapshot session returned no watermark");
        }
        progress.recordAvailabilitySession(snapshot);

        String cursor = null;
        try (ThrottledBulkWriter.Session session = bulkWriter.open("reindex-availability", deadline)) {
            do {
                ExportPages.SkuAvailabilitySnapshotPage page =
                        exports.skuAvailabilities(snapshot.snapshotId(), cursor);
                for (CatalogPayloads.SkuAvailability availability : nullSafe(page.items())) {
                    session.add(writer.availabilityPatch(productsIndex, availability.skuId(),
                                    Boolean.TRUE.equals(availability.isInStock()),
                                    availability.availabilityRevision(),
                                    Timestamps.iso(availability.observedAt()), Timestamps.nowIso(), "reindex"),
                            256);
                    if (availability.observedAt() != null) {
                        metrics.snapshotAge(SOURCE_AVAILABILITY,
                                java.time.Duration.between(availability.observedAt(), Instant.now()).toSeconds());
                    }
                }
                progress.addAvailabilitySnapshots(nullSafe(page.items()).size());
                cursor = page.nextCursor();
                progress.recordAvailabilityCursor(cursor);
            } while (cursor != null && !cursor.isBlank());
        }
        metrics.snapshotExport(SOURCE_AVAILABILITY, "success");
    }

    /**
     * Rating build, after the catalog so the SKUs exist. Each aggregate is applied with an
     * {@code _update_by_query} over the product's SKUs, guarded by {@code review_revision}
     * exactly like the event path (ADR-016).
     */
    private void buildRating(String productsIndex, ReindexProgressState progress, Instant deadline)
            throws IOException {
        if (!properties.export().reviewEnabled()) {
            log.info("review rating export is disabled; new SKUs keep their 0/0 default");
            return;
        }
        String after = null;
        do {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("rating build exceeded the run deadline");
            }
            ExportPages.RatingExportPage page = exports.ratingAggregates(after);
            progress.recordOffsets(SOURCE_REVIEW, page.replayFromOffsets());
            for (CatalogPayloads.RatingAggregate aggregate : nullSafe(page.items())) {
                writer.reviewMetricsPatch(productsIndex, aggregate.productId(),
                        aggregate.averageRating() == null ? 0.0 : aggregate.averageRating(),
                        aggregate.totalReviews() == null ? 0L : aggregate.totalReviews(),
                        aggregate.reviewRevision(), Timestamps.nowIso(), "reindex");
                if (aggregate.observedAt() != null) {
                    metrics.snapshotAge(SOURCE_REVIEW,
                            java.time.Duration.between(aggregate.observedAt(), Instant.now()).toSeconds());
                }
            }
            progress.addRatingAggregates(nullSafe(page.items()).size());
            after = page.nextAfterProductId();
        } while (after != null && !after.isBlank());
        metrics.snapshotExport(SOURCE_REVIEW, "success");
    }

    // ------------------------------------------------------------------ cutover

    /**
     * Verifies the new index before moving traffic, then swaps every alias in one atomic
     * call so no instant exists where two indices hold the same write alias.
     */
    private void cutover(String productsIndex, String categoriesIndex) throws IOException {
        indexAdmin.refresh(productsIndex);
        indexAdmin.refresh(categoriesIndex);

        Optional<String> currentProducts = indexAdmin.resolveAlias(properties.aliases().products());
        if (currentProducts.isPresent()) {
            long before = indexAdmin.countDocuments(currentProducts.get());
            long after = indexAdmin.countDocuments(productsIndex);
            double drift = before == 0 ? 0 : Math.abs(after - before) / (double) before;
            if (drift > properties.reindex().maxDocCountDriftRatio()) {
                throw new IllegalStateException("document count drifted by " + Math.round(drift * 100)
                        + "% (" + before + " -> " + after + "), refusing to cut over");
            }
        }

        indexAdmin.swapAliases(productsIndex,
                List.of(properties.aliases().products(), properties.aliases().productsWrite()));
        indexAdmin.swapAliases(categoriesIndex,
                List.of(properties.aliases().categories(), properties.aliases().categoriesWrite()));
    }

    // ------------------------------------------------------------------ helpers

    /** No snapshot may be imported without its replay boundary, or catch-up has no start. */
    private void requireOffsets(ReindexProgressState progress) {
        // Inventory is absent on purpose: it publishes no Kafka replay offset, and catch-up
        // resumes from its change feed at the session watermark instead (ADR-019).
        List<String> required = properties.export().reviewEnabled()
                ? List.of(SOURCE_CATEGORY, SOURCE_PRODUCT, SOURCE_REVIEW)
                : List.of(SOURCE_CATEGORY, SOURCE_PRODUCT);
        for (String source : required) {
            if (!progress.hasOffsetsFor(source)) {
                throw new IllegalStateException(source + " export returned no replay_from_offsets");
            }
        }
    }

    /**
     * A control event during Build or Catch-up means the category tree changed under the
     * run: the index would cut over with a stale path, so the run is marked
     * {@code SUPERSEDED} and the aliases are left alone (ADR-017).
     */
    private void failIfSuperseded(String runId, String productsIndex, String categoriesIndex, String reason,
                                  String actor, Instant startedAt, ReindexProgressState progress) throws IOException {
        if (!control.supersedePending()) {
            return;
        }
        log.warn("reindex run {} superseded by a catalog control event", runId);
        discard(productsIndex);
        discard(categoriesIndex);
        save(runId, "SUPERSEDED", null, reason, actor, startedAt, Instant.now(), progress, productsIndex,
                new String[]{"RUN_SUPERSEDED", "a catalog-reindex-requested event arrived during this run"});
        metrics.reindexState("SUPERSEDED", 1);
        throw new SupersededException();
    }

    /** Signals that a run stopped because a newer control event took priority. */
    public static class SupersededException extends RuntimeException {
        SupersededException() {
            super("reindex run superseded by a newer catalog control event");
        }
    }

    private Optional<CategoryDocument> lookupCategory(String categoriesIndex,
                                                      Map<String, CategoryDocument> cache,
                                                      String categoryId) {
        if (categoryId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.computeIfAbsent(categoryId, id -> {
            try {
                var response = indexAdmin.getDocument(categoriesIndex, id, CategoryDocument.class);
                return response.orElse(null);
            } catch (IOException e) {
                log.warn("category enrichment for {} failed during reindex: {}", id, e.getMessage());
                return null;
            }
        }));
    }

    private void discard(String index) {
        if (index == null) {
            return;
        }
        try {
            indexAdmin.deleteIndex(index);
        } catch (IOException e) {
            log.warn("could not clean up index {} after a failed run: {}", index, e.getMessage());
        }
    }

    private void save(String runId, String status, String phase, String reason, String actor,
                      Instant startedAt, Instant finishedAt, ReindexProgressState progress,
                      String targetIndex, String[] failure) throws IOException {
        runs.saveRun(new ReindexRunDocument(
                ReindexRunDocument.DOC_KIND, runId, status, phase, reason, actor,
                startedAt, finishedAt, Instant.now(),
                failure == null ? null : failure[0],
                failure == null ? null : failure[1],
                targetIndex,
                progress.toMap(),
                List.of(progress.offsetsAsMap()),
                progress.checkpointAsMap()));
    }

    private void saveQuietly(String runId, String status, String phase, String reason, String actor,
                             Instant startedAt, Instant finishedAt, ReindexProgressState progress,
                             String targetIndex, String[] failure) {
        try {
            save(runId, status, phase, reason, actor, startedAt, finishedAt, progress, targetIndex, failure);
        } catch (IOException e) {
            log.error("could not persist the final state of reindex run {}: {}", runId, e.getMessage());
        }
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** Rough serialised size, good enough to keep bulk requests under the byte ceiling. */
    private static long estimateBytes(Map<String, Object> fields) {
        return String.valueOf(fields).length() * 2L;
    }
}
