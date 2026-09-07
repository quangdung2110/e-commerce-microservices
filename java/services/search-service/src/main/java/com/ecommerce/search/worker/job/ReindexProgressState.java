package com.ecommerce.search.worker.job;

import com.ecommerce.search.worker.client.ExportPages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable progress of one reindex run, persisted into the run document after each phase so
 * the admin status endpoint and a resume both have something to read.
 */
public final class ReindexProgressState {

    private final AtomicLong categoriesExported = new AtomicLong();
    private final AtomicLong productsExported = new AtomicLong();
    private final AtomicLong availabilitySnapshotsImported = new AtomicLong();
    private final AtomicLong ratingAggregatesImported = new AtomicLong();
    private volatile Long catchUpLag;

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

    public void addCategories(long count) {
        categoriesExported.addAndGet(count);
    }

    public void addProducts(long count) {
        productsExported.addAndGet(count);
    }

    public void addAvailabilitySnapshots(long count) {
        availabilitySnapshotsImported.addAndGet(count);
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

    public void setCatchUpLag(Long lag) {
        this.catchUpLag = lag;
    }

    /** Records the offsets of one source; ignores a repeat, since pages repeat them. */
    public void recordOffsets(String source, List<ExportPages.ReplayOffset> offsets) {
        if (offsets != null && !offsets.isEmpty()) {
            replayOffsets.putIfAbsent(source, List.copyOf(offsets));
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

    /** The Inventory handoff point a resume or a cutover check has to reason about. */
    public Map<String, Object> checkpointAsMap() {
        if (availabilitySession == null) {
            return Map.of();
        }
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("snapshot_id", availabilitySession.snapshotId());
        session.put("export_watermark", availabilitySession.exportWatermark());
        session.put("expires_at", availabilitySession.expiresAt());
        session.put("cursor", availabilityCursor);
        session.put("sequence", availabilitySequence);
        session.put("handoff_proven", availabilityHandoffProven);
        session.put("deferred_skus", deferredAvailabilitySkus.size());
        return Map.of("inventory_availability", session);
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
}
