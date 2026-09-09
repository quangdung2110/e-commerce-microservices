package com.ecommerce.search.worker.job;

import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.document.ReindexRunDocument;
import com.ecommerce.search.domain.event.CatalogPayloads;
import com.ecommerce.search.domain.event.CloudEventEnvelope;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.repository.es.IndexAdmin;
import com.ecommerce.search.repository.es.IndexTemplates;
import com.ecommerce.search.repository.es.ProjectionWriter;
import com.ecommerce.search.repository.es.ThrottledBulkWriter;
import com.ecommerce.search.worker.ReindexControlCoordinator;
import com.ecommerce.search.worker.client.ExportPages;
import com.ecommerce.search.worker.client.InventoryExportException;
import com.ecommerce.search.worker.client.RatingExportSessionException;
import com.ecommerce.search.worker.client.SnapshotExportClient;
import com.ecommerce.search.worker.handler.AvailabilityEventHandler;
import com.ecommerce.search.worker.handler.BatchExecutor;
import com.ecommerce.search.worker.handler.CatalogProjection;
import com.ecommerce.search.worker.handler.EventBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code WF-SEARCH-009} — the three-phase full reindex.
 *
 * <p>Build imports every snapshot into a new versioned index; Catch-up replays each topic
 * from the offsets the owning services captured, writing <em>directly</em> into that index
 * rather than through the write alias; Cutover moves both aliases in a single atomic
 * {@code _aliases} call. Rollback is the reverse swap (ADR-006).
 *
 * <p>The run fails rather than cuts over whenever a snapshot export fails, an export omits
 * its {@code replay_from_offsets}, the Inventory handoff cannot be proved at a single
 * watermark, a SKU is still left with availability and no document, catch-up does not
 * converge, or a control event supersedes the run.
 */
@Component
public class FullReindexJob {

    private static final Logger log = LoggerFactory.getLogger(FullReindexJob.class);

    private static final String SOURCE_CATEGORY = "category";
    private static final String SOURCE_PRODUCT = "product";
    private static final String SOURCE_AVAILABILITY = "inventory_availability";
    private static final String SOURCE_REVIEW = "review";
    private static final String AVAILABILITY_CHANGES_JOB = "reindex-availability-changes";

    private final SearchProperties properties;
    private final SnapshotExportClient exports;
    private final IndexAdmin indexAdmin;
    private final ProjectionWriter writer;
    private final ThrottledBulkWriter bulkWriter;
    private final JobStateRepository runs;
    private final ReindexCatchUp catchUp;
    private final ReindexControlCoordinator control;
    private final AvailabilityEventHandler availabilityHandler;
    private final BatchExecutor executor;
    private final SearchMetrics metrics;

    public FullReindexJob(SearchProperties properties,
                          SnapshotExportClient exports,
                          IndexAdmin indexAdmin,
                          ProjectionWriter writer,
                          ThrottledBulkWriter bulkWriter,
                          JobStateRepository runs,
                          ReindexCatchUp catchUp,
                          ReindexControlCoordinator control,
                          AvailabilityEventHandler availabilityHandler,
                          BatchExecutor executor,
                          SearchMetrics metrics) {
        this.properties = properties;
        this.exports = exports;
        this.indexAdmin = indexAdmin;
        this.writer = writer;
        this.bulkWriter = bulkWriter;
        this.runs = runs;
        this.catchUp = catchUp;
        this.control = control;
        this.availabilityHandler = availabilityHandler;
        this.executor = executor;
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
            settleDeferredAvailability(productsIndex, progress, deadline);

            failIfSuperseded(runId, productsIndex, categoriesIndex, reason, actor, startedAt, progress);
            requireAvailabilityHandoff(progress);

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
     * Availability build, after the catalog so the target documents exist.
     *
     * <p>Inventory pins one {@code export_watermark} per session, and a session returns the
     * whole registered SKU set rather than a delta — so running exactly one session end to
     * end, then its change feed from that same watermark, is what proves coverage. A session
     * that expires mid-paging invalidates that proof: the pages already read belong to a
     * watermark that no longer exists, and splicing them onto a feed at the new one is not a
     * handoff. The only recovery is a new session paged from its first page, which is why
     * this is a loop rather than a resume ({@code WF-SEARCH-007}).
     *
     * <p>Re-importing rows costs nothing: the {@code availability_revision} guard turns them
     * into no-ops or lifts them to a newer state, so nothing is rolled back between attempts.
     */
    private void buildAvailability(String productsIndex, ReindexProgressState progress, Instant deadline)
            throws IOException {
        int maxSessions = properties.export().availabilitySnapshotMaxSessions();
        for (int attempt = 1; attempt <= maxSessions; attempt++) {
            ExportPages.SnapshotSession session = openAvailabilitySession(progress);
            try {
                importAvailabilitySnapshot(session, productsIndex, progress, deadline);
                drainAvailabilityChanges(productsIndex, progress, deadline);
                progress.markAvailabilityHandoffProven();
                metrics.availabilityExport("ok");
                return;
            } catch (InventoryExportException e) {
                metrics.availabilityExport(e.reason().metricStatus());
                log.warn("availability bootstrap attempt {} of {} restarts from a new session: {}",
                        attempt, maxSessions, e.getMessage());
            }
        }
        // Repeatedly outliving a 24-hour session is a capacity problem, not a blip, so the
        // run stops and alerts instead of looping.
        metrics.availabilityExport("fail");
        throw new IllegalStateException("availability bootstrap used all " + maxSessions
                + " permitted snapshot sessions without completing one end to end");
    }

    private ExportPages.SnapshotSession openAvailabilitySession(ReindexProgressState progress) {
        ExportPages.SnapshotSession session = exports.createInventorySnapshotSession();
        if (session == null || session.snapshotId() == null || session.exportWatermark() == null) {
            throw new IllegalStateException("inventory snapshot session returned no watermark");
        }
        progress.recordAvailabilitySession(session);
        recordSessionTtl(session);
        log.info("availability bootstrap pinned to session {} at watermark {} (expires {})",
                session.snapshotId(), session.exportWatermark(), session.expiresAt());
        return session;
    }

    /** Pages one session from its first page into the documents the catalog build created. */
    private void importAvailabilitySnapshot(ExportPages.SnapshotSession session, String productsIndex,
                                            ReindexProgressState progress, Instant deadline) throws IOException {
        String cursor = null;
        try (ThrottledBulkWriter.Session bulk = bulkWriter.open("reindex-availability", deadline)) {
            do {
                requireBefore(deadline, "availability build");
                recordSessionTtl(session);
                ExportPages.SkuAvailabilitySnapshotPage page =
                        exports.skuAvailabilities(session.snapshotId(), cursor);
                for (CatalogPayloads.SkuAvailability availability : nullSafe(page.items())) {
                    bulk.add(writer.availabilityPatch(productsIndex, availability.skuId(),
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

            bulk.flush();
            absorbSnapshotFailures(bulk.failures(), progress);
        }
    }

    /**
     * A missing target is not an error here: Product's snapshot was taken first, so a SKU
     * created after it has no document yet. The fact is deferred rather than stored, because
     * catch-up will create the document and register readiness, and registration obliges
     * Inventory to publish the current availability again (ADR-019).
     */
    private void absorbSnapshotFailures(List<ThrottledBulkWriter.ItemFailure> failures,
                                        ReindexProgressState progress) {
        List<ThrottledBulkWriter.ItemFailure> fatal = new ArrayList<>();
        for (ThrottledBulkWriter.ItemFailure failure : failures) {
            if (failure.isDocumentMissing()) {
                progress.deferAvailability(failure.id());
            } else {
                fatal.add(failure);
            }
        }
        if (!fatal.isEmpty()) {
            throw new IllegalStateException("availability import hit item failures a retry cannot fix: " + fatal);
        }
    }

    /**
     * Reads the change feed from the session watermark until it is drained.
     *
     * <p>The feed carries the same CloudEvents as the live topic, so it goes through the
     * same handler and the same guard; only the transport differs. Inventory publishes no
     * Kafka replay offset, so this feed — not a topic seek — is how the new index stays
     * current until cutover hands it back to the live indexer.
     */
    private void drainAvailabilityChanges(String productsIndex, ReindexProgressState progress, Instant deadline) {
        long after = progress.availabilityResumeSequence();
        while (true) {
            requireBefore(deadline, "availability change feed");
            ExportPages.ProjectionChangePage page = exports.changes(after);

            EventBatch batch = new EventBatch(productsIndex);
            for (CloudEventEnvelope event : nullSafe(page.items())) {
                availabilityHandler.handle(properties.topics().stock(), event, batch);
            }
            // The commit verdict is meaningless here — there is no offset to hold. What
            // matters is which targets existed, and that is what the two lists carry.
            executor.execute(AVAILABILITY_CHANGES_JOB, batch);
            batch.settledDocumentIds().forEach(progress::resolveDeferredAvailability);
            batch.missingDocumentIds().forEach(progress::deferAvailability);

            Long next = page.nextAfterSequence();
            if (next == null) {
                return;
            }
            after = next;
            progress.recordAvailabilitySequence(after);
        }
    }

    /**
     * Waits for the deferred SKUs to resolve themselves after Product catch-up.
     *
     * <p>Nothing is re-sent from here. Catch-up created each missing document and registered
     * its readiness; Inventory answers that by republishing the SKU's current availability,
     * which arrives on the same change feed. What this does is drain that feed until the
     * deferred set empties — and refuse the cutover if it never does.
     */
    private void settleDeferredAvailability(String productsIndex, ReindexProgressState progress,
                                            Instant runDeadline) {
        Instant deadline = earliest(runDeadline, Instant.now().plus(properties.reindex().catchUpDeadline()));
        while (true) {
            drainAvailabilityChanges(productsIndex, progress, deadline);
            Set<String> deferred = progress.deferredAvailabilitySkus();
            if (deferred.isEmpty()) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException(deferred.size() + " sku(s) still have availability with no target "
                        + "document after product catch-up, refusing to cut over: " + sample(deferred));
            }
            log.info("waiting on {} deferred availability sku(s) to be republished after readiness registration",
                    deferred.size());
            sleep(properties.reindex().catchUpPollTimeout());
        }
    }

    private void recordSessionTtl(ExportPages.SnapshotSession session) {
        if (session.expiresAt() != null) {
            metrics.snapshotSessionTtl(
                    java.time.Duration.between(Instant.now(), session.expiresAt()).toSeconds());
        }
    }

    private static void requireBefore(Instant deadline, String what) {
        if (Instant.now().isAfter(deadline)) {
            throw new IllegalStateException(what + " exceeded its deadline");
        }
    }

    private static Instant earliest(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static String sample(Set<String> ids) {
        return ids.stream().limit(10).toList() + (ids.size() > 10 ? " ..." : "");
    }

    private static void sleep(java.time.Duration duration) {
        try {
            Thread.sleep(Math.max(0, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("waiting on deferred availability was interrupted", e);
        }
    }

    /**
     * Rating build, after the catalog so the SKUs exist.
     *
     * <p>Review pins the {@code t.review.events} offsets when the session is created, before
     * anything is scanned, so a session that dies mid-scan takes its replay boundary with it:
     * the aggregates already imported were read against offsets that will never be seeked to.
     * The recovery is a new session paged from its first page, which is why this loops the
     * way the availability build does ({@code WF-SEARCH-009} step 3).
     *
     * <p>Re-importing aggregates is free — the {@code review_revision} guard turns a repeat
     * into a no-op — so nothing is undone between attempts.
     */
    private void buildRating(String productsIndex, ReindexProgressState progress, Instant deadline)
            throws IOException {
        if (!properties.export().reviewEnabled()) {
            log.info("review rating export is disabled; new SKUs keep their 0/0 default");
            return;
        }
        int maxSessions = properties.export().ratingExportMaxSessions();
        for (int attempt = 1; attempt <= maxSessions; attempt++) {
            ExportPages.RatingExportSession session = openRatingSession(progress);
            try {
                importRatingSnapshot(session, productsIndex, progress, deadline);
                metrics.snapshotExport(SOURCE_REVIEW, "success");
                return;
            } catch (RatingExportSessionException e) {
                metrics.snapshotExport(SOURCE_REVIEW, "session_expired");
                log.warn("rating build attempt {} of {} restarts from a new session: {}",
                        attempt, maxSessions, e.getMessage());
            }
        }
        // Outliving the session twice is a capacity problem, not a blip: stop and alert
        // rather than loop, exactly as the availability bootstrap does.
        metrics.snapshotExport(SOURCE_REVIEW, "fail");
        throw new IllegalStateException("rating build used all " + maxSessions
                + " permitted export sessions without completing one end to end");
    }

    /**
     * Opens a session and takes its offsets as the review replay boundary, replacing any a
     * superseded session left behind.
     *
     * <p>The replacement is the point: seeking to the older session's offsets would start the
     * replay after this session's snapshot was taken and drop every mutation in between.
     */
    private ExportPages.RatingExportSession openRatingSession(ReindexProgressState progress) {
        ExportPages.RatingExportSession session = exports.createRatingExportSession();
        if (session == null || session.sessionId() == null
                || session.replayFromOffsets() == null || session.replayFromOffsets().isEmpty()) {
            throw new IllegalStateException("review rating export session returned no replay_from_offsets");
        }
        progress.recordRatingSession(session);
        progress.replaceOffsets(SOURCE_REVIEW, session.replayFromOffsets());
        log.info("rating build pinned to review export session {} (expires {})",
                session.sessionId(), session.expiresAt());
        return session;
    }

    /**
     * Pages one session from its first page, applying each aggregate with an
     * {@code _update_by_query} over the product's SKUs, guarded by {@code review_revision}
     * exactly like the event path (ADR-016).
     *
     * <p>This is the one build step that cannot use {@code _bulk}: the aggregate is keyed by
     * {@code product_id} while a document is addressed by {@code _id = sku_id}, and Search
     * keeps no reverse lookup from one to the other ({@code SEARCH-INV-005}).
     */
    private void importRatingSnapshot(ExportPages.RatingExportSession session, String productsIndex,
                                      ReindexProgressState progress, Instant deadline) throws IOException {
        String after = null;
        do {
            requireBefore(deadline, "rating build");
            ExportPages.RatingExportPage page = exports.ratingAggregates(session.sessionId(), after);
            requireSameSession(session, page);
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
    }

    /**
     * A page belonging to another session would have been scanned against offsets this run
     * never recorded, so the import it contributes to could not be replayed from. Treated as
     * the session being gone rather than as a page to skip.
     */
    private static void requireSameSession(ExportPages.RatingExportSession session,
                                           ExportPages.RatingExportPage page) {
        if (page.sessionId() != null && !page.sessionId().equals(session.sessionId())) {
            throw new RatingExportSessionException("review returned a page of session "
                    + page.sessionId() + " while " + session.sessionId() + " was being read");
        }
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
     * Cutover needs the Inventory handoff to be provable, not merely attempted: one session
     * paged end to end, its change feed picked up at that same watermark, and no SKU left
     * whose availability has no target document ({@code WF-SEARCH-009}).
     */
    private void requireAvailabilityHandoff(ReindexProgressState progress) {
        if (!progress.isAvailabilityHandoffProven()) {
            throw new IllegalStateException(
                    "no inventory snapshot session was completed at a single watermark, refusing to cut over");
        }
        Set<String> deferred = progress.deferredAvailabilitySkus();
        if (!deferred.isEmpty()) {
            throw new IllegalStateException(deferred.size()
                    + " sku(s) still have unresolved availability, refusing to cut over: " + sample(deferred));
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
