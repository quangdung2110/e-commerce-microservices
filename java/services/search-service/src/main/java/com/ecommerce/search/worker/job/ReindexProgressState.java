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
    private final AtomicLong stockSnapshotsImported = new AtomicLong();
    private final AtomicLong ratingAggregatesImported = new AtomicLong();
    private volatile Long catchUpLag;

    /** Replay boundaries per source, all captured by the owning service before its scan. */
    private final Map<String, List<ExportPages.ReplayOffset>> replayOffsets = new LinkedHashMap<>();

    public void addCategories(long count) {
        categoriesExported.addAndGet(count);
    }

    public void addProducts(long count) {
        productsExported.addAndGet(count);
    }

    public void addStockSnapshots(long count) {
        stockSnapshotsImported.addAndGet(count);
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
        map.put("stock_snapshots_imported", stockSnapshotsImported.get());
        map.put("rating_aggregates_imported", ratingAggregatesImported.get());
        map.put("catch_up_lag", catchUpLag);
        return map;
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
