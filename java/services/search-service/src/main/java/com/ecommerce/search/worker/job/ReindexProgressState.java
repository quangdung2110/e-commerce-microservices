package com.ecommerce.search.worker.job;

import com.ecommerce.search.domain.document.ReindexRunDocument;
import com.ecommerce.search.worker.client.ExportPages;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable progress of one reindex run, persisted into the run document at every checkpoint so
 * the admin status endpoint and a resume both have something to read.
 *
 * <p>A resume rebuilds this object from the stored document ({@link #restore}) and continues at
 * {@link #stage()} using the cursor of that stage. Everything a stage needs in order to carry on
 * is therefore kept here and written out by {@link #checkpointAsMap}; nothing is derived from
 * the process that died.
 */
public final class ReindexProgressState {

    /**
     * The next unit of work, in execution order. A stage is only recorded as done by moving on
     * to the following one, so a crash re-runs at most the stage it was in; every stage is safe
     * to repeat because each write is guarded by a revision.
     */
    public enum Stage {
        CREATE_INDICES, BUILD_CATEGORIES, BUILD_CATALOG, BUILD_AVAILABILITY, BUILD_RATING,
        CATCH_UP, VERIFY, CUTOVER, DONE;

        public boolean before(Stage other) {
            return ordinal() < other.ordinal();
        }
    }

    private final AtomicLong categoriesExported = new AtomicLong();
    private final AtomicLong productsExported = new AtomicLong();
    private final AtomicLong availabilitySnapshotsImported = new AtomicLong();
    private final AtomicLong ratingAggregatesImported = new AtomicLong();
    private volatile Long catchUpLag;

    private volatile Stage stage = Stage.CREATE_INDICES;
    private volatile String productsIndex;
    private volatile String categoriesIndex;
    private volatile String productCardsIndex;
    private volatile String categoriesCursor;
    private volatile String productsCursor;
    private volatile int resumeAttempts;
    private volatile String previousProductsIndex;
    private volatile String previousCategoriesIndex;
    private volatile Instant cutoverAt;
    private volatile String tailReplay;
    private volatile Instant catchUpEndedAt;
    private final Map<String, Long> catchUpEndPositions = new ConcurrentHashMap<>();

    /** Replay boundaries per source, all captured by the owning service before its scan. */
    private final Map<String, List<ExportPages.ReplayOffset>> replayOffsets = new LinkedHashMap<>();

    /**
     * The Inventory snapshot session the availability build is paging. It is checkpointed
     * rather than derived, because a session expires 24 hours after creation and a resume
     * has to know which watermark the pages it already imported belong to (ADR-019).
     */
    private volatile ExportPages.SnapshotSession availabilitySession;
    private volatile String availabilityCursor;
    private volatile Long availabilitySequence;
    private volatile boolean availabilityHandoffProven;

    /**
     * SKUs whose availability arrived before Product had created their document.
     *
     * <p>Only the identifier is kept, never the fact. The fact comes back on its own: the
     * catch-up phase creates the document and registers readiness, and registration obliges
     * Inventory to publish the current availability again even with no stock mutation
     * (ADR-019). Storing the fact here would duplicate that guarantee and could serve a
     * value already superseded.
     */
    private final Set<String> deferredAvailabilitySkus = ConcurrentHashMap.newKeySet();

    /**
     * The Review export session the rating build is paging. Checkpointed for the same reason
     * as the Inventory one: the offsets already recorded belong to <em>this</em> session, and
     * a restart has to be able to say which (ADR-016).
     */
    private volatile ExportPages.RatingExportSession ratingSession;
    private volatile String ratingAfter;

    // ------------------------------------------------------------------ stage and indices

    public Stage stage() {
        return stage;
    }

    public void advanceTo(Stage next) {
        this.stage = next;
    }

    public String productsIndex() {
        return productsIndex;
    }

    public String categoriesIndex() {
        return categoriesIndex;
    }

    public String productCardsIndex() { return productCardsIndex; }
    public void recordProductCardsIndex(String index) { this.productCardsIndex = index; }

    public void recordIndices(String productsIndex, String categoriesIndex) {
        this.productsIndex = productsIndex;
        this.categoriesIndex = categoriesIndex;
    }

    /**
     * The indices the cutover replaced, written down <em>before</em> the aliases move so a crash
     * between the swap and the final save still leaves the way back, and read by rollback.
     */
    public void recordPrevious(String previousProductsIndex, String previousCategoriesIndex, Instant at) {
        this.previousProductsIndex = previousProductsIndex;
        this.previousCategoriesIndex = previousCategoriesIndex;
        this.cutoverAt = at;
    }

    public String previousProductsIndex() {
        return previousProductsIndex;
    }

    public String previousCategoriesIndex() {
        return previousCategoriesIndex;
    }

    /** {@code COMPLETE} or {@code INCOMPLETE}: whether the post-cutover tail replay read everything. */
    public void recordTailReplay(String outcome) {
        this.tailReplay = outcome;
    }

    public int resumeAttempts() {
        return resumeAttempts;
    }

    public void setResumeAttempts(int attempts) {
        this.resumeAttempts = attempts;
    }

    // ------------------------------------------------------------------ build cursors

    public String categoriesCursor() {
        return categoriesCursor;
    }

    public void recordCategoriesCursor(String cursor) {
        this.categoriesCursor = cursor;
    }

    public String productsCursor() {
        return productsCursor;
    }

    public void recordProductsCursor(String cursor) {
        this.productsCursor = cursor;
    }

    public void addCategories(long count) {
        categoriesExported.addAndGet(count);
    }

    public void addProducts(long count) {
        productsExported.addAndGet(count);
    }

    public void addAvailabilitySnapshots(long count) {
        availabilitySnapshotsImported.addAndGet(count);
    }

    public ExportPages.SnapshotSession availabilitySession() {
        return availabilitySession;
    }

    public String availabilityCursor() {
        return availabilityCursor;
    }

    /**
     * Pins a fresh availability build to a session, discarding everything the previous one
     * established.
     *
     * <p>Dropping the cursor and the sequence is the point: a half-paged set at the old
     * watermark spliced onto a change feed at the new one is not a valid handoff, so the
     * new session has to be paged from its first page ({@code WF-SEARCH-007}).
     */
    public void recordAvailabilitySession(ExportPages.SnapshotSession session) {
        this.availabilitySession = session;
        this.availabilityCursor = null;
        this.availabilitySequence = null;
        this.availabilityHandoffProven = false;
        this.availabilitySnapshotsImported.set(0);
    }

    public void recordAvailabilityCursor(String cursor) {
        this.availabilityCursor = cursor;
    }

    /** How far the change feed has been read; the resume point for later drains. */
    public void recordAvailabilitySequence(long sequence) {
        this.availabilitySequence = sequence;
    }

    /** The sequence to continue the change feed from, or the session watermark. */
    public long availabilityResumeSequence() {
        if (availabilitySequence != null) {
            return availabilitySequence;
        }
        return availabilitySession == null || availabilitySession.exportWatermark() == null
                ? 0L : availabilitySession.exportWatermark();
    }

    /**
     * Marks that one session was paged end to end and its change feed picked up from that
     * same watermark. Cutover is gated on it, because nothing else distinguishes a complete
     * import from one abandoned halfway.
     */
    public void markAvailabilityHandoffProven() {
        this.availabilityHandoffProven = true;
    }

    public boolean isAvailabilityHandoffProven() {
        return availabilityHandoffProven;
    }

    public void deferAvailability(String skuId) {
        deferredAvailabilitySkus.add(skuId);
    }

    public void resolveDeferredAvailability(String skuId) {
        deferredAvailabilitySkus.remove(skuId);
    }

    public Set<String> deferredAvailabilitySkus() {
        return Set.copyOf(deferredAvailabilitySkus);
    }

    public void addRatingAggregates(long count) {
        ratingAggregatesImported.addAndGet(count);
    }

    public ExportPages.RatingExportSession ratingSession() {
        return ratingSession;
    }

    public String ratingAfter() {
        return ratingAfter;
    }

    public void recordRatingAfter(String after) {
        this.ratingAfter = after;
    }

    /**
     * Pins a fresh rating build to a session, discarding what the previous one counted.
     *
     * <p>The count is reset rather than carried over because a new session is paged from its
     * first page: keeping the old total would double-count every aggregate re-imported. The
     * offsets are replaced separately by the caller, since they arrive with the session
     * itself rather than with the pages.
     */
    public void recordRatingSession(ExportPages.RatingExportSession session) {
        this.ratingSession = session;
        this.ratingAfter = null;
        this.ratingAggregatesImported.set(0);
    }

    public void setCatchUpLag(Long lag) {
        this.catchUpLag = lag;
    }

    // ------------------------------------------------------------------ catch-up boundary

    /**
     * Where each catch-up partition had been read to when the phase converged
     * ({@code topic-partition -> next offset}). A tail replay after the cutover starts here:
     * the live consumer wrote everything after this point into the index that was still serving,
     * so the new index has to read it once more.
     */
    public void recordCatchUpEnd(Map<String, Long> positions, Instant endedAt) {
        this.catchUpEndPositions.clear();
        this.catchUpEndPositions.putAll(positions);
        this.catchUpEndedAt = endedAt;
    }

    public Map<String, Long> catchUpEndPositions() {
        return Map.copyOf(catchUpEndPositions);
    }

    public Instant catchUpEndedAt() {
        return catchUpEndedAt;
    }

    /** Records the offsets of one source; ignores a repeat, since pages repeat them. */
    public void recordOffsets(String source, List<ExportPages.ReplayOffset> offsets) {
        if (offsets != null && !offsets.isEmpty()) {
            replayOffsets.putIfAbsent(source, List.copyOf(offsets));
        }
    }

    /**
     * Replaces the offsets of one source, which a repeated {@link #recordOffsets} will not do.
     *
     * <p>Only a source that can restart its export session needs this. Seeking to a superseded
     * session's offsets would start the replay later than the surviving snapshot was taken and
     * silently drop every mutation in between, so the stale entry must go rather than win.
     */
    public void replaceOffsets(String source, List<ExportPages.ReplayOffset> offsets) {
        if (offsets != null && !offsets.isEmpty()) {
            replayOffsets.put(source, List.copyOf(offsets));
        }
    }

    public boolean hasOffsetsFor(String source) {
        return replayOffsets.containsKey(source);
    }

    /** Every offset across all sources, which is what the catch-up consumer seeks to. */
    public List<ExportPages.ReplayOffset> allOffsets() {
        List<ExportPages.ReplayOffset> all = new ArrayList<>();
        replayOffsets.values().forEach(all::addAll);
        return all;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("categories_exported", categoriesExported.get());
        map.put("products_exported", productsExported.get());
        // The wire name stays `stock_snapshots_imported`: it is what the v2 OpenAPI
        // declares for ReindexProgress, and renaming it would break the admin client.
        map.put("stock_snapshots_imported", availabilitySnapshotsImported.get());
        map.put("rating_aggregates_imported", ratingAggregatesImported.get());
        map.put("catch_up_lag", catchUpLag);
        return map;
    }

    /**
     * Everything a resume or a cutover check has to reason about: the stage, both target
     * indices, one cursor per build source, and the two export sessions.
     *
     * <p>Both session IDs are checkpointed because {@code WF-SEARCH-009} requires them as
     * run metrics, and because a support question about a bad rating in a finished index
     * starts with which session exported it.
     */
    public Map<String, Object> checkpointAsMap() {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("stage", stage.name());
        checkpoint.put("products_index", productsIndex);
        checkpoint.put("categories_index", categoriesIndex);
        checkpoint.put("product_cards_index", productCardsIndex);
        checkpoint.put("categories_cursor", categoriesCursor);
        checkpoint.put("products_cursor", productsCursor);
        checkpoint.put("resume_attempts", resumeAttempts);
        if (cutoverAt != null) {
            checkpoint.put("previous_products_index", previousProductsIndex);
            checkpoint.put("previous_categories_index", previousCategoriesIndex);
            checkpoint.put("cutover_at", cutoverAt);
        }
        if (tailReplay != null) {
            checkpoint.put("tail_replay", tailReplay);
        }
        if (availabilitySession != null) {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("snapshot_id", availabilitySession.snapshotId());
            session.put("export_watermark", availabilitySession.exportWatermark());
            session.put("created_at", availabilitySession.createdAt());
            session.put("expires_at", availabilitySession.expiresAt());
            session.put("cursor", availabilityCursor);
            session.put("sequence", availabilitySequence);
            session.put("handoff_proven", availabilityHandoffProven);
            session.put("deferred_skus", deferredAvailabilitySkus.size());
            session.put("deferred_sku_ids", new ArrayList<>(deferredAvailabilitySkus));
            checkpoint.put("inventory_availability", session);
        }
        if (ratingSession != null) {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("session_id", ratingSession.sessionId());
            session.put("expires_at", ratingSession.expiresAt());
            session.put("after_product_id", ratingAfter);
            checkpoint.put("review_rating", session);
        }
        if (catchUpEndedAt != null) {
            checkpoint.put("catch_up_ended_at", catchUpEndedAt);
            checkpoint.put("catch_up_end_positions", new LinkedHashMap<>(catchUpEndPositions));
        }
        return checkpoint;
    }

    public Map<String, Object> offsetsAsMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        replayOffsets.forEach((source, offsets) -> map.put(source, offsets.stream()
                .map(offset -> Map.<String, Object>of(
                        "topic", offset.topic(),
                        "partition", offset.partition(),
                        "offset", offset.offset()))
                .toList()));
        return map;
    }

    // ------------------------------------------------------------------ restore

    /**
     * Rebuilds the state of a run that was stopped mid-way from the document it last saved.
     * A document without a {@code stage} (written before this existed) restarts from the
     * beginning, which is always safe.
     */
    @SuppressWarnings("unchecked")
    public static ReindexProgressState restore(ReindexRunDocument run) {
        ReindexProgressState state = new ReindexProgressState();
        Map<String, Object> checkpoint = run.checkpointOrEmpty();

        Object stageValue = checkpoint.get("stage");
        if (stageValue instanceof String name) {
            try {
                state.stage = Stage.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                state.stage = Stage.CREATE_INDICES;
            }
        }
        state.productsIndex = asString(checkpoint.get("products_index"));
        state.categoriesIndex = asString(checkpoint.get("categories_index"));
        state.productCardsIndex = asString(checkpoint.get("product_cards_index"));
        state.categoriesCursor = asString(checkpoint.get("categories_cursor"));
        state.productsCursor = asString(checkpoint.get("products_cursor"));
        state.resumeAttempts = (int) asLong(checkpoint.get("resume_attempts"));
        state.previousProductsIndex = asString(checkpoint.get("previous_products_index"));
        state.previousCategoriesIndex = asString(checkpoint.get("previous_categories_index"));
        state.cutoverAt = asInstant(checkpoint.get("cutover_at"));
        state.tailReplay = asString(checkpoint.get("tail_replay"));

        Map<String, Object> progress = run.progress() == null ? Map.of() : run.progress();
        state.categoriesExported.set(asLong(progress.get("categories_exported")));
        state.productsExported.set(asLong(progress.get("products_exported")));
        state.availabilitySnapshotsImported.set(asLong(progress.get("stock_snapshots_imported")));
        state.ratingAggregatesImported.set(asLong(progress.get("rating_aggregates_imported")));
        state.catchUpLag = progress.get("catch_up_lag") == null ? null : asLong(progress.get("catch_up_lag"));

        if (run.replayFromOffsets() != null && !run.replayFromOffsets().isEmpty()) {
            run.replayFromOffsets().get(0).forEach((source, value) -> {
                if (value instanceof List<?> list) {
                    List<ExportPages.ReplayOffset> offsets = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            offsets.add(new ExportPages.ReplayOffset(
                                    String.valueOf(map.get("topic")),
                                    (int) asLong(map.get("partition")),
                                    asLong(map.get("offset"))));
                        }
                    }
                    state.replayOffsets.put(source, offsets);
                }
            });
        }

        if (checkpoint.get("inventory_availability") instanceof Map<?, ?> raw) {
            Map<String, Object> session = (Map<String, Object>) raw;
            state.availabilitySession = new ExportPages.SnapshotSession(
                    asString(session.get("snapshot_id")),
                    session.get("export_watermark") == null ? null : asLong(session.get("export_watermark")),
                    asInstant(session.get("created_at")),
                    asInstant(session.get("expires_at")));
            state.availabilityCursor = asString(session.get("cursor"));
            state.availabilitySequence = session.get("sequence") == null ? null : asLong(session.get("sequence"));
            state.availabilityHandoffProven = Boolean.TRUE.equals(session.get("handoff_proven"));
            if (session.get("deferred_sku_ids") instanceof List<?> ids) {
                ids.forEach(id -> state.deferredAvailabilitySkus.add(String.valueOf(id)));
            }
        }
        if (checkpoint.get("review_rating") instanceof Map<?, ?> raw) {
            Map<String, Object> session = (Map<String, Object>) raw;
            state.ratingSession = new ExportPages.RatingExportSession(
                    asString(session.get("session_id")),
                    asInstant(session.get("expires_at")),
                    state.replayOffsets.getOrDefault("review", List.of()));
            state.ratingAfter = asString(session.get("after_product_id"));
        }
        if (checkpoint.get("catch_up_ended_at") != null) {
            state.catchUpEndedAt = asInstant(checkpoint.get("catch_up_ended_at"));
            if (checkpoint.get("catch_up_end_positions") instanceof Map<?, ?> positions) {
                positions.forEach((key, value) ->
                        state.catchUpEndPositions.put(String.valueOf(key), asLong(value)));
            }
        }
        return state;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Instant asInstant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
