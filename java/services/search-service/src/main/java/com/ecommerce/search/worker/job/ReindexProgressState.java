package com.ecommerce.search.worker.job;

import com.ecommerce.search.worker.client.ExportPages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public void addCategories(long count) {
        categoriesExported.addAndGet(count);
    }

    public void addProducts(long count) {
        productsExported.addAndGet(count);
    }

    public void addAvailabilitySnapshots(long count) {
        availabilitySnapshotsImported.addAndGet(count);
    }

    /** Records the session a fresh availability build is pinned to, clearing any old cursor. */
    public void recordAvailabilitySession(ExportPages.SnapshotSession session) {
        this.availabilitySession = session;
        this.availabilityCursor = null;
    }

    public void recordAvailabilityCursor(String cursor) {
        this.availabilityCursor = cursor;
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
