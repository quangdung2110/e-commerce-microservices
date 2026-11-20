package com.ecommerce.search.common;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The minimum metric set from {@code docs/search/operations.md §4}.
 *
 * <p>No identifier is ever used as a label: {@code sku_id}, {@code product_id},
 * {@code category_id}, search terms and {@code user_id} have unbounded cardinality and
 * belong in structured logs instead.
 *
 * <p>Every gauge reads from a cell this class owns. {@code MeterRegistry.gauge(name, number)}
 * would keep only a weak reference to the boxed number, which the garbage collector reclaims
 * after the job that set it has finished, leaving the gauge at {@code NaN}.
 */
@Component
public class SearchMetrics {

    /** Every value of {@code search_reindex_state{phase}}; exactly one is 1 at any time. */
    static final List<String> REINDEX_PHASES = List.of(
            "STARTED", "BUILDING", "CATCHING_UP", "CUTOVER", "COMPLETED",
            "FAILED", "SUPERSEDED", "CANCELLED", "ROLLED_BACK");

    private static final class Cell {
        private volatile double value = Double.NaN;

        double get() {
            return value;
        }
    }

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Cell> cells = new ConcurrentHashMap<>();

    public SearchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    private void gauge(String name, double value, String... tagKeyValues) {
        String key = name + Tags.of(tagKeyValues);
        Cell cell = cells.computeIfAbsent(key, unused -> {
            Cell created = new Cell();
            Gauge.builder(name, created, Cell::get)
                    .tags(tagKeyValues)
                    .strongReference(true)
                    .register(registry);
            return created;
        });
        cell.value = value;
    }

    public void esQuery(String index, String queryType, Duration took) {
        registry.timer("es_query_duration_seconds", "index", index, "query_type", queryType)
                .record(took);
    }

    public void indexing(String topic, String eventType, String status) {
        registry.counter("search_indexing_total", "topic", topic, "event_type", eventType, "status", status)
                .increment();
    }

    public void indexingLag(String topic, Duration lag) {
        registry.timer("search_indexing_lag_seconds", "topic", topic).record(lag);
    }

    public void eventIgnored(String topic, String eventType) {
        registry.counter("search_event_ignored_total", "topic", topic, "event_type", eventType).increment();
    }

    public void documentMissing(String topic, String eventType) {
        registry.counter("search_document_missing_total", "topic", topic, "event_type", eventType).increment();
    }

    /** {@code operation} is register or revoke; {@code status} is ok, conflict or fail. */
    public void readiness(String operation, String status) {
        registry.counter("search_inventory_readiness_total", "operation", operation, "status", status)
                .increment();
    }

    public void payloadInvalid(String topic, String eventType) {
        registry.counter("search_payload_invalid_total", "topic", topic, "event_type", eventType).increment();
    }

    public void categoryEnrichmentMiss(String topic, String eventType) {
        registry.counter("search_category_enrichment_miss_total", "topic", topic, "event_type", eventType).increment();
    }

    public void zombieUpdateBlocked(String topic, String eventType) {
        registry.counter("search_zombie_update_blocked_total", "topic", topic, "event_type", eventType).increment();
    }

    public void jobDuration(String job, Duration duration) {
        registry.timer("search_job_duration_seconds", "job", job).record(duration);
    }

    public Timer.Sample startJob() {
        return Timer.start(registry);
    }

    /** Marks {@code phase} as the current reindex state and clears every other phase. */
    public void reindexState(String phase) {
        for (String known : REINDEX_PHASES) {
            gauge("search_reindex_state", known.equals(phase) ? 1 : 0, "phase", known);
        }
    }

    public void snapshotExport(String source, String status) {
        registry.counter("search_" + source + "_snapshot_export_total", "status", status).increment();
    }

    public void snapshotAge(String source, double seconds) {
        gauge("search_" + source + "_snapshot_age_seconds", seconds);
    }

    /**
     * Outcome of one Inventory availability bootstrap.
     *
     * <p>Named separately from {@link #snapshotExport} because the two {@code 410} codes are
     * statuses of their own: {@code ok}, {@code snapshot_session_expired},
     * {@code replay_window_expired}, {@code fail} ({@code operations.md §4}).
     */
    public void availabilityExport(String status) {
        registry.counter("search_inventory_availability_export_total", "status", status).increment();
    }

    /** Time left on the snapshot session being paged, so expiry can be alerted on early. */
    public void snapshotSessionTtl(double seconds) {
        gauge("search_inventory_snapshot_session_ttl_seconds", seconds);
    }

    public void bulkDispatched(String job, int actions, long bytes) {
        registry.summary("search_es_bulk_actions", "job", job).record(actions);
        registry.summary("search_es_bulk_bytes", "job", job).record(bytes);
    }

    public void bulkThrottled(String job, Duration waited) {
        registry.counter("search_es_bulk_throttled_seconds", "job", job).increment(waited.toNanos() / 1_000_000_000d);
    }

    public void bulkRetry(String job, String reason) {
        registry.counter("search_es_bulk_retries_total", "job", job, "reason", reason).increment();
    }

    public void bulkInFlight(String job, int inFlight) {
        gauge("search_es_bulk_in_flight", inFlight, "job", job);
    }

    public void orphanCategoryNode() {
        registry.counter("search_category_orphan_node_total").increment();
    }

    /** {@code kind} is the reindex lifecycle event: resumed, abandoned, auto_started, cancelled... */
    public void reindexLifecycle(String kind) {
        registry.counter("search_reindex_lifecycle_total", "kind", kind).increment();
    }

    /** {@code outcome} is ok or fail; {@code check} is sample_diff, smoke or count_drift. */
    public void cutoverCheck(String check, String outcome) {
        registry.counter("search_reindex_cutover_check_total", "check", check, "outcome", outcome).increment();
    }

    /** Number of obsolete physical indices the cleanup job removed. */
    public void indexCleaned(String template) {
        registry.counter("search_index_cleanup_total", "template", template).increment();
    }
}
