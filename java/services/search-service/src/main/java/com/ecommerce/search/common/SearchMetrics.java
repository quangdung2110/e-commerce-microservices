package com.ecommerce.search.common;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The minimum metric set from {@code docs/search/operations.md §4}.
 *
 * <p>No identifier is ever used as a label: {@code sku_id}, {@code product_id},
 * {@code category_id}, search terms and {@code user_id} have unbounded cardinality and
 * belong in structured logs instead.
 */
@Component
public class SearchMetrics {

    private final MeterRegistry registry;

    public SearchMetrics(MeterRegistry registry) {
        this.registry = registry;
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

    public void reindexState(String phase, double value) {
        registry.gauge("search_reindex_state", io.micrometer.core.instrument.Tags.of("phase", phase), value);
    }

    public void snapshotExport(String source, String status) {
        registry.counter("search_" + source + "_snapshot_export_total", "status", status).increment();
    }

    public void snapshotAge(String source, double seconds) {
        registry.gauge("search_" + source + "_snapshot_age_seconds", seconds);
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
        registry.gauge("search_es_bulk_in_flight", io.micrometer.core.instrument.Tags.of("job", job), inFlight);
    }

    public void orphanCategoryNode() {
        registry.counter("search_category_orphan_node_total").increment();
    }
}
