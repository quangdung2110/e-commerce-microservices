package com.ecommerce.search.worker.job;

import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.document.LeaseLockDocument;
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
import com.ecommerce.search.worker.job.ReindexProgressState.Stage;
import com.ecommerce.search.worker.productcards.ProductCardProjector;
import com.ecommerce.search.repository.es.ProductCardIndex;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@code WF-SEARCH-009} — the three-phase full reindex.
 *
 * <p>Build preserves deletion guards and imports every snapshot into a new versioned index; Catch-up replays each topic
 * from the offsets the owning services captured, writing <em>directly</em> into that index
 * rather than through the write alias; Cutover verifies the new index and moves every alias in
 * a single atomic {@code _aliases} call. Rollback is the reverse swap (ADR-006, ADR-022).
 *
 * <p>The run is a sequence of {@link Stage}s whose position and cursors are saved at every
 * page. The process owns the {@code full-reindex} lease while it works and renews it from a
 * heartbeat thread; if it dies the lease expires, {@link ReindexWatchdog} notices, and
 * {@code run} is called again with the saved document to carry on from the saved stage.
 *
 * <p>The run fails rather than cuts over whenever a snapshot export fails, an export omits
 * its {@code replay_from_offsets}, the Inventory handoff cannot be proved at a single
 * watermark, a SKU is still left with availability and no document, catch-up does not
 * converge, the new index fails verification, or a control event supersedes the run. It
 * ends {@code CANCELLED} when an admin asks, and does nothing at all when it loses its lease.
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
    private final ReindexVerifier verifier;
    private final ReindexTombstoneTransfer tombstones;
    private final ProductCardProjector productCards;

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
                          SearchMetrics metrics,
                          ReindexVerifier verifier,
                          ReindexTombstoneTransfer tombstones, ProductCardProjector productCards) {
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
        this.verifier = verifier;
        this.tombstones = tombstones;
        this.productCards = productCards;
    }

    // ------------------------------------------------------------------ one run

    /** State shared by the stages of a single run. */
    private final class Run {
        final String runId;
        final String reason;
        final String actor;
        final Instant startedAt;
        final Instant deadline;
        final LeaseLockDocument lease;
        final ReindexProgressState progress;
        volatile boolean leaseLost;
        volatile String lastStatus;

        Run(String runId, String reason, String actor, Instant startedAt, Instant deadline,
            LeaseLockDocument lease, ReindexProgressState progress) {
            this.runId = runId;
            this.reason = reason;
            this.actor = actor;
            this.startedAt = startedAt;
            this.deadline = deadline;
            this.lease = lease;
            this.progress = progress;
        }

        /**
         * Called between units of work. Stops the run when it lost its lease, ran out of time or
         * was cancelled; a run that reached the verify-and-swap step can no longer be cancelled.
         */
        void check() {
            if (leaseLost) {
                throw new LeaseLostException();
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("run exceeded its deadline of " + properties.reindex().runDeadline());
            }
            if (progress.stage().before(Stage.VERIFY)) {
                try {
                    if (runs.isCancelRequested(runId)) {
                        throw new CancelledException();
                    }
                } catch (IOException e) {
                    log.warn("cannot read the cancellation request of run {}, assuming none: {}", runId, e.getMessage());
                }
            }
        }
    }

    /**
     * Runs the whole pipeline off the request thread, from the beginning or — when
     * {@code resumeFrom} is the saved document of an abandoned run — from its saved stage.
     * The caller has already acquired {@code lease}; this method releases it.
     */
    @Async("reindexExecutor")
    public void run(String runId, String reason, String actor, LeaseLockDocument lease,
                    ReindexRunDocument resumeFrom) {
        boolean resuming = resumeFrom != null;
        Instant startedAt = resuming && resumeFrom.startedAt() != null ? resumeFrom.startedAt() : Instant.now();
        ReindexProgressState progress = resuming ? ReindexProgressState.restore(resumeFrom) : new ReindexProgressState();
        Run run = new Run(runId, reason, actor, startedAt,
                Instant.now().plus(properties.reindex().runDeadline()), lease, progress);

        ScheduledExecutorService heartbeat = startHeartbeat(run);
        try {
            if (resuming) {
                log.info("resuming reindex run {} at stage {}", runId, progress.stage());
            }
            execute(run);
        } catch (SupersededException e) {
            log.warn("reindex run {} ended superseded", runId);
        } catch (CancelledException e) {
            finishCancelled(run);
        } catch (LeaseLostException e) {
            log.error("reindex run {} lost its lease and stops without touching its state; "
                    + "whoever holds the lease now owns the run", runId);
        } catch (Exception e) {
            fail(run, e);
        } finally {
            heartbeat.shutdownNow();
            if (!run.leaseLost) {
                try {
                    runs.releaseLease(lease);
                } catch (IOException e) {
                    log.warn("could not release the reindex lease, it expires on its own: {}", e.getMessage());
                }
            }
        }
    }

    private void execute(Run run) throws IOException {
        ReindexProgressState p = run.progress;
        persist(run);

        if (p.stage() == Stage.CREATE_INDICES) {
            createIndices(run);
            advance(run, Stage.BUILD_CATEGORIES);
        }
        if (p.stage() == Stage.BUILD_CATEGORIES) {
            tombstones.preserve(p.productsIndex(), p.categoriesIndex(), run.deadline, run::check);
            buildCategories(run);
            advance(run, Stage.BUILD_CATALOG);
        }
        if (p.stage() == Stage.BUILD_CATALOG) {
            buildCatalog(run);
            advance(run, Stage.BUILD_AVAILABILITY);
        }
        if (p.stage() == Stage.BUILD_AVAILABILITY) {
            buildAvailability(run);
            advance(run, Stage.BUILD_RATING);
        }
        if (p.stage() == Stage.BUILD_RATING) {
            buildRating(run);
            requireOffsets(p);
            failIfSuperseded(run);
            advance(run, Stage.CATCH_UP);
        }
        if (p.stage() == Stage.CATCH_UP) {
            catchUp.replay(run.runId, p.productsIndex(), p.categoriesIndex(), p, run.deadline, run::check);
            persist(run);
            settleDeferredAvailability(run);
            failIfSuperseded(run);
            requireAvailabilityHandoff(p);
            advance(run, Stage.VERIFY);
        }
        if (p.stage() == Stage.VERIFY) {
            failIfSuperseded(run);
            // Also collect deletes applied to the serving generation during build/catch-up.
            tombstones.preserve(p.productsIndex(), p.categoriesIndex(), run.deadline, run::check);
            verifier.verify(indexAdmin.resolveAlias(properties.aliases().products()), p.productsIndex(),
                    p.catchUpEndedAt());
            advance(run, Stage.CUTOVER);
        }
        if (p.stage() == Stage.CUTOVER) {
            cutover(run);
            advance(run, Stage.DONE);
            log.info("reindex run {} completed; aliases now serve {} and {}",
                    run.runId, p.productsIndex(), p.categoriesIndex());
        }
        if (p.stage() == Stage.DONE) {
            afterCutover(run);
        }
    }

    private void advance(Run run, Stage next) throws IOException {
        run.progress.advanceTo(next);
        persist(run);
    }

    // ------------------------------------------------------------------ heartbeat

    private ScheduledExecutorService startHeartbeat(Run run) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "search-reindex-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long interval = properties.lifecycle().leaseRenewInterval().toMillis();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                Instant now = Instant.now();
                if (!runs.renewLease(run.lease, now, now.plus(properties.lifecycle().leaseDuration()))) {
                    run.leaseLost = true;
                    log.error("the full-reindex lease of run {} moved to another owner", run.runId);
                }
            } catch (Exception e) {
                log.warn("heartbeat of run {} failed and will be retried: {}", run.runId, e.getMessage());
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
        return scheduler;
    }

    // ------------------------------------------------------------------ build phases

    private void createIndices(Run run) throws IOException {
        String categories = indexAdmin.createVersionedIndex(
                IndexTemplates.CATEGORIES, indexAdmin.nextVersion(IndexTemplates.CATEGORIES));
        String products = indexAdmin.createVersionedIndex(
                IndexTemplates.PRODUCTS, indexAdmin.nextVersion(IndexTemplates.PRODUCTS));
        // Recorded before anything else happens, so a failure never leaks an index nobody knows.
        run.progress.recordIndices(products, categories);
        persist(run);
    }

    /** Category export runs first so the Product build can enrich from a fresh tree. */
    private void buildCategories(Run run) throws IOException {
        ReindexProgressState p = run.progress;
        String cursor = p.categoriesCursor();
        try (ThrottledBulkWriter.Session session = bulkWriter.open("reindex-categories", run.deadline)) {
            do {
                run.check();
                ExportPages.CategoryExportPage page = exports.categories(cursor);
                p.recordOffsets(SOURCE_CATEGORY, page.replayFromOffsets());
                for (CatalogPayloads.CategorySnapshot category : nullSafe(page.categories())) {
                    if (!CatalogProjection.hasValidPath(category)) {
                        log.warn("skipping exported category {} with an unusable path", category.categoryId());
                        continue;
                    }
                    Map<String, Object> fields = CatalogProjection.toCategoryFields(category);
                    session.add(writer.categoryUpsert(p.categoriesIndex(), category.categoryId(), fields,
                                    category.categoryRevision(), Timestamps.nowIso(), "reindex"),
                            estimateBytes(fields));
                }
                // The cursor is only saved once the page is in the index.
                session.flush();
                p.addCategories(nullSafe(page.categories()).size());
                cursor = page.nextCursor();
                p.recordCategoriesCursor(cursor);
                persist(run);
            } while (cursor != null && !cursor.isBlank());
        }
        indexAdmin.refresh(p.categoriesIndex());
        metrics.snapshotExport(SOURCE_CATEGORY, "success");
    }

    /**
     * Product build. Category enrichment reads the freshly built category index directly,
     * not the alias, so the new SKU documents carry the same tree the run exported.
     */
    private void buildCatalog(Run run) throws IOException {
        ReindexProgressState p = run.progress;
        Map<String, CategoryDocument> categoryCache = new HashMap<>();
        String cursor = p.productsCursor();
        try (ThrottledBulkWriter.Session session = bulkWriter.open("reindex-catalog", run.deadline)) {
            do {
                run.check();
                ExportPages.ProductExportPage page = exports.products(cursor);
                p.recordOffsets(SOURCE_PRODUCT, page.replayFromOffsets());
                for (ExportPages.ExportedProduct product : nullSafe(page.products())) {
                    Optional<CategoryDocument> category =
                            lookupCategory(p.categoriesIndex(), categoryCache, product.categoryId());
                    for (ExportPages.ExportedSku sku : nullSafe(product.skus())) {
                        Map<String, Object> fields = CatalogProjection.toFields(
                                product.toSnapshot(), sku.toSnapshot(), category);
                        session.add(writer.catalogUpsert(p.productsIndex(), sku.skuId(), fields,
                                        product.catalogRevision(), Timestamps.nowIso(), "reindex"),
                                estimateBytes(fields));
                    }
                }
                session.flush();
                p.addProducts(nullSafe(page.products()).size());
                cursor = page.nextCursor();
                p.recordProductsCursor(cursor);
                persist(run);
            } while (cursor != null && !cursor.isBlank());
        }
        indexAdmin.refresh(p.productsIndex());
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
     * this is a loop rather than a resume of the cursor ({@code WF-SEARCH-007}). A run resumed
     * after a crash does continue the saved session at its saved cursor first, and falls back to
     * this loop if the session is gone.
     *
     * <p>Re-importing rows costs nothing: the {@code availability_revision} guard turns them
     * into no-ops or lifts them to a newer state, so nothing is rolled back between attempts.
     */
    private void buildAvailability(Run run) throws IOException {
        ReindexProgressState p = run.progress;
        int maxSessions = properties.export().availabilitySnapshotMaxSessions();
        for (int attempt = 1; attempt <= maxSessions; attempt++) {
            ExportPages.SnapshotSession session;
            String startCursor;
            if (attempt == 1 && p.availabilitySession() != null && p.availabilitySession().snapshotId() != null) {
                session = p.availabilitySession();
                startCursor = p.availabilityCursor();
            } else {
                session = openAvailabilitySession(p);
                startCursor = null;
            }
            try {
                importAvailabilitySnapshot(run, session, startCursor);
                drainAvailabilityChanges(run, run.deadline);
                p.markAvailabilityHandoffProven();
                persist(run);
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

    /** Pages one session from {@code startCursor} into the documents the catalog build created. */
    private void importAvailabilitySnapshot(Run run, ExportPages.SnapshotSession session, String startCursor)
            throws IOException {
        ReindexProgressState p = run.progress;
        String cursor = startCursor;
        int absorbed = 0;
        try (ThrottledBulkWriter.Session bulk = bulkWriter.open("reindex-availability", run.deadline)) {
            do {
                run.check();
                recordSessionTtl(session);
                ExportPages.SkuAvailabilitySnapshotPage page =
                        exports.skuAvailabilities(session.snapshotId(), cursor);
                for (CatalogPayloads.SkuAvailability availability : nullSafe(page.items())) {
                    bulk.add(writer.availabilityPatch(p.productsIndex(), availability.skuId(),
                                    Boolean.TRUE.equals(availability.isInStock()),
                                    availability.availabilityRevision(),
                                    Timestamps.iso(availability.observedAt()), Timestamps.nowIso(), "reindex"),
                            256);
                    if (availability.observedAt() != null) {
                        metrics.snapshotAge(SOURCE_AVAILABILITY,
                                java.time.Duration.between(availability.observedAt(), Instant.now()).toSeconds());
                    }
                }
                bulk.flush();
                List<ThrottledBulkWriter.ItemFailure> failures = bulk.failures();
                absorbSnapshotFailures(failures.subList(absorbed, failures.size()), p);
                absorbed = failures.size();
                p.addAvailabilitySnapshots(nullSafe(page.items()).size());
                cursor = page.nextCursor();
                p.recordAvailabilityCursor(cursor);
                persist(run);
            } while (cursor != null && !cursor.isBlank());
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
    private void drainAvailabilityChanges(Run run, Instant deadline) throws IOException {
        ReindexProgressState p = run.progress;
        long after = p.availabilityResumeSequence();
        while (true) {
            run.check();
            requireBefore(deadline, "availability change feed");
            ExportPages.ProjectionChangePage page = exports.changes(after);

            EventBatch batch = new EventBatch(p.productsIndex());
            for (CloudEventEnvelope event : nullSafe(page.items())) {
                availabilityHandler.handle(properties.topics().stock(), event, batch);
            }
            // The commit verdict is meaningless here — there is no offset to hold. What
            // matters is which targets existed, and that is what the two lists carry.
            executor.execute(AVAILABILITY_CHANGES_JOB, batch);
            batch.settledDocumentIds().forEach(p::resolveDeferredAvailability);
            batch.missingDocumentIds().forEach(p::deferAvailability);

            Long next = page.nextAfterSequence();
            if (next == null) {
                return;
            }
            after = next;
            p.recordAvailabilitySequence(after);
            persist(run);
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
    private void settleDeferredAvailability(Run run) throws IOException {
        Instant deadline = earliest(run.deadline, Instant.now().plus(properties.reindex().catchUpDeadline()));
        while (true) {
            drainAvailabilityChanges(run, deadline);
            Set<String> deferred = run.progress.deferredAvailabilitySkus();
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
     * way the availability build does ({@code WF-SEARCH-009} step 3). A run resumed after a
     * crash first continues the saved session at the saved product id.
     *
     * <p>Re-importing aggregates is free — the {@code review_revision} guard turns a repeat
     * into a no-op — so nothing is undone between attempts.
     */
    private void buildRating(Run run) throws IOException {
        ReindexProgressState p = run.progress;
        if (!properties.export().reviewEnabled()) {
            log.info("review rating export is disabled; new SKUs keep their 0/0 default");
            return;
        }
        // The rating update works on the refreshed view: documents written by the stages
        // before this one must be visible or their update would conflict and be skipped.
        indexAdmin.refresh(p.productsIndex());
        int maxSessions = properties.export().ratingExportMaxSessions();
        for (int attempt = 1; attempt <= maxSessions; attempt++) {
            ExportPages.RatingExportSession session;
            String startAfter;
            if (attempt == 1 && p.ratingSession() != null && p.ratingSession().sessionId() != null) {
                session = p.ratingSession();
                startAfter = p.ratingAfter();
            } else {
                session = openRatingSession(p);
                startAfter = null;
            }
            try {
                importRatingSnapshot(run, session, startAfter);
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
     * Pages one session, applying each aggregate with an {@code _update_by_query} over the
     * product's SKUs, guarded by {@code review_revision} exactly like the event path (ADR-016).
     *
     * <p>This is the one build step that cannot use {@code _bulk}: the aggregate is keyed by
     * {@code product_id} while a document is addressed by {@code _id = sku_id}, and Search
     * keeps no reverse lookup from one to the other ({@code SEARCH-INV-005}).
     */
    private void importRatingSnapshot(Run run, ExportPages.RatingExportSession session, String startAfter)
            throws IOException {
        ReindexProgressState p = run.progress;
        String after = startAfter;
        do {
            run.check();
            ExportPages.RatingExportPage page = exports.ratingAggregates(session.sessionId(), after);
            requireSameSession(session, page);
            for (CatalogPayloads.RatingAggregate aggregate : nullSafe(page.items())) {
                writer.reviewMetricsPatch(p.productsIndex(), aggregate.productId(),
                        aggregate.averageRating() == null ? 0.0 : aggregate.averageRating(),
                        aggregate.totalReviews() == null ? 0L : aggregate.totalReviews(),
                        aggregate.reviewRevision(), Timestamps.nowIso(), "reindex");
                if (aggregate.observedAt() != null) {
                    metrics.snapshotAge(SOURCE_REVIEW,
                            java.time.Duration.between(aggregate.observedAt(), Instant.now()).toSeconds());
                }
            }
            p.addRatingAggregates(nullSafe(page.items()).size());
            after = page.nextAfterProductId();
            p.recordRatingAfter(after);
            persist(run);
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
     * Moves every alias to the new indices in one {@code _aliases} request, after re-checking
     * that the lease and the run are still valid. The indices being replaced are written down
     * first, so a crash between the swap and the final save cannot lose the way back.
     */
    private void cutover(Run run) throws IOException {
        ReindexProgressState p = run.progress;
        failIfSuperseded(run);
        if (run.leaseLost || !runs.holdsLease(run.lease)) {
            throw new LeaseLostException();
        }

        Optional<String> previousProducts = indexAdmin.resolveAlias(properties.aliases().products());
        Optional<String> previousCategories = indexAdmin.resolveAlias(properties.aliases().categories());
        boolean alreadySwapped = previousProducts.filter(p.productsIndex()::equals).isPresent();
        if (!alreadySwapped) {
            p.recordPrevious(previousProducts.orElse(null), previousCategories.orElse(null), Instant.now());
            persist(run);

            indexAdmin.refresh(p.productsIndex());
            indexAdmin.refresh(p.categoriesIndex());
            Map<String, List<String>> targets = new java.util.LinkedHashMap<>();
            targets.put(p.productsIndex(),
                    List.of(properties.aliases().products(), properties.aliases().productsWrite()));
            targets.put(p.categoriesIndex(),
                    List.of(properties.aliases().categories(), properties.aliases().categoriesWrite()));
            // Build against the PRIVATE verified SKU index. Keep the live projector out
            // until the paired aliases have moved and the resume checkpoint is durable.
            Optional<String> previousCards = indexAdmin.resolveAlias(ProductCardIndex.ALIAS);
            try (var prepared = productCards.prepareForCutover(p.productsIndex(), run::check)) {
                p.recordProductCardsIndex(prepared.index());
                persist(run);
                targets.put(prepared.index(), List.of(ProductCardIndex.ALIAS));
                prepared.checkOwnership();
                run.check();
                if (!runs.holdsLease(run.lease)) throw new LeaseLostException();
                indexAdmin.swapAliasesAtomically(targets);
                prepared.published();
            }
            if (previousCards.isPresent()) runs.markIndexRetired(previousCards.get(), run.runId, Instant.now());
        } else {
            log.info("run {} resumed after its aliases had already moved; finishing the bookkeeping", run.runId);
        }

        Instant retiredAt = Instant.now();
        for (String replaced : new String[]{p.previousProductsIndex(), p.previousCategoriesIndex()}) {
            if (replaced != null && !replaced.equals(p.productsIndex()) && !replaced.equals(p.categoriesIndex())) {
                runs.markIndexRetired(replaced, run.runId, retiredAt);
            }
        }
    }

    /**
     * What follows a completed cutover and cannot undo it: satisfy the control requests the run
     * covered, and read once more the stretch the live indexer applied to the old index after
     * catch-up ended.
     */
    private void afterCutover(Run run) {
        ReindexProgressState p = run.progress;
        control.satisfiedBy(run.startedAt);
        boolean tailComplete = true;
        try {
            drainAvailabilityChanges(run, Instant.now().plus(properties.reindex().catchUpDeadline()));
        } catch (Exception e) {
            tailComplete = false;
            log.error("availability tail of run {} did not finish: {}", run.runId, e.getMessage());
        }
        tailComplete &= catchUp.replayTail(run.runId, p.productsIndex(), p.categoriesIndex(), p, () -> { });
        p.recordTailReplay(tailComplete ? "COMPLETE" : "INCOMPLETE");
        saveQuietly(run, "COMPLETED", null, Instant.now(), null);
    }

    // ------------------------------------------------------------------ ending a run

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
     * A control event after the run started means the category tree changed under it: the index
     * would cut over with a stale path, so the run is marked {@code SUPERSEDED}, its indices are
     * discarded and the aliases are left alone (ADR-017). The request stays stored, so the next
     * run is started for it.
     */
    private void failIfSuperseded(Run run) throws IOException {
        if (!control.supersedePending(run.startedAt)) {
            return;
        }
        log.warn("reindex run {} superseded by a catalog control event", run.runId);
        discard(run.progress);
        saveFinal(run, "SUPERSEDED", new String[]{"RUN_SUPERSEDED",
                "a catalog-reindex-requested event arrived during this run"});
        metrics.reindexState("SUPERSEDED");
        throw new SupersededException();
    }

    private void finishCancelled(Run run) {
        log.warn("reindex run {} cancelled", run.runId);
        discard(run.progress);
        saveQuietly(run, "CANCELLED", null, Instant.now(),
                new String[]{"RUN_CANCELLED", "cancelled by an administrator"});
        metrics.reindexState("CANCELLED");
        metrics.reindexLifecycle("cancelled");
        try {
            runs.clearCancel(run.runId);
        } catch (IOException e) {
            log.warn("could not clear the cancellation request of run {}: {}", run.runId, e.getMessage());
        }
    }

    private void fail(Run run, Exception e) {
        log.error("reindex run {} failed: {}", run.runId, e.getMessage(), e);
        ReindexProgressState p = run.progress;
        try {
            if (p.productsIndex() != null && indexAdmin.resolveAlias(properties.aliases().products())
                    .filter(p.productsIndex()::equals).isPresent()) {
                // The aliases moved before the failure: the run did its job, so it is not a
                // failure and the indices it serves must not be deleted.
                p.advanceTo(Stage.DONE);
                saveQuietly(run, "COMPLETED", null, Instant.now(), null);
                return;
            }
        } catch (IOException ignored) {
            // Cannot tell where the aliases are: keep the indices rather than risk deleting a serving one.
            saveQuietly(run, "FAILED", null, Instant.now(),
                    new String[]{"REINDEX_FAILED", String.valueOf(e.getMessage())});
            metrics.reindexState("FAILED");
            return;
        }
        discard(p);
        saveQuietly(run, "FAILED", null, Instant.now(), new String[]{"REINDEX_FAILED", String.valueOf(e.getMessage())});
        metrics.reindexState("FAILED");
    }

    /** Deletes the indices a run built, unless an alias already serves them. */
    public void discard(ReindexProgressState p) {
        for (String index : new String[]{p.productsIndex(), p.categoriesIndex(), p.productCardsIndex()}) {
            if (index == null) {
                continue;
            }
            try {
                boolean serving = indexAdmin.resolveAlias(properties.aliases().products()).filter(index::equals).isPresent()
                        || indexAdmin.resolveAlias(properties.aliases().categories()).filter(index::equals).isPresent()
                        || indexAdmin.resolveAlias(ProductCardIndex.ALIAS).filter(index::equals).isPresent();
                if (!serving) {
                    indexAdmin.deleteIndex(index);
                }
            } catch (IOException e) {
                log.warn("could not clean up index {} after a failed run: {}", index, e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ persistence

    private static String statusOf(Stage stage) {
        return switch (stage) {
            case CREATE_INDICES, BUILD_CATEGORIES, BUILD_CATALOG, BUILD_AVAILABILITY, BUILD_RATING -> "BUILDING";
            case CATCH_UP -> "CATCHING_UP";
            case VERIFY, CUTOVER -> "CUTOVER";
            case DONE -> "COMPLETED";
        };
    }

    /** Saves the run at its current stage. Called at every page and every stage change. */
    private void persist(Run run) throws IOException {
        String status = statusOf(run.progress.stage());
        boolean done = run.progress.stage() == Stage.DONE;
        if (!status.equals(run.lastStatus)) {
            run.lastStatus = status;
            metrics.reindexState(status);
        }
        save(run, status, done ? null : status, done ? Instant.now() : null, null);
    }

    private void saveFinal(Run run, String status, String[] failure) throws IOException {
        save(run, status, null, Instant.now(), failure);
    }

    private void saveQuietly(Run run, String status, String phase, Instant finishedAt, String[] failure) {
        try {
            save(run, status, phase, finishedAt, failure);
        } catch (IOException e) {
            log.error("could not persist the final state of reindex run {}: {}", run.runId, e.getMessage());
        }
    }

    private void save(Run run, String status, String phase, Instant finishedAt, String[] failure)
            throws IOException {
        ReindexProgressState p = run.progress;
        runs.saveRun(new ReindexRunDocument(
                ReindexRunDocument.DOC_KIND, run.runId, status, phase, run.reason, run.actor,
                run.startedAt, finishedAt, Instant.now(),
                failure == null ? null : failure[0],
                failure == null ? null : failure[1],
                p.productsIndex(),
                p.toMap(),
                List.of(p.offsetsAsMap()),
                p.checkpointAsMap()));
    }

    // ------------------------------------------------------------------ helpers

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

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** Rough serialised size, good enough to keep bulk requests under the byte ceiling. */
    private static long estimateBytes(Map<String, Object> fields) {
        return String.valueOf(fields).length() * 2L;
    }

    // ------------------------------------------------------------------ control flow

    /** Signals that a run stopped because a newer control event took priority. */
    public static class SupersededException extends RuntimeException {
        SupersededException() {
            super("reindex run superseded by a newer catalog control event");
        }
    }

    /** An administrator asked for this run to stop. */
    static class CancelledException extends RuntimeException {
        CancelledException() {
            super("reindex run cancelled");
        }
    }

    /** Another process holds the lease, so this one must stop writing run state. */
    static class LeaseLostException extends RuntimeException {
        LeaseLostException() {
            super("reindex lease lost");
        }
    }
}
