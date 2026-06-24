package com.ecommerce.search.util;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Central metrics recorder for the Search Service.
 *
 * <p>Wraps Micrometer to provide typed methods for the 5 essential metrics:
 * <ul>
 *   <li>{@code search.es.query.duration} — histogram: ES query latency by index and query_type</li>
 *   <li>{@code search.indexing.total} — counter: event indexing success/fail by event_type</li>
 *   <li>{@code search.es.error.total} — counter: ES errors by error_type</li>
 *   <li>{@code search.event.ignored.total} — counter: skipped events by topic and event_type</li>
 *   <li>{@code search.document.missing.total} — counter: document_missing_exception by topic/docId</li>
 *   <li>{@code search.payload.invalid.total} — counter: invalid payload by topic/event_type</li>
 *   <li>{@code search.category.enrichment.miss.total} — counter: category lookup miss</li>
 * </ul>
 *
 * <p>Kafka consumer lag ({@code kafka.consumer.lag}) and HTTP metrics
 * ({@code http.server.requests}) are automatically provided by Spring Boot
 * Actuator and Micrometer's Kafka consumer metrics.
 */
@Component
@RequiredArgsConstructor
public class MetricsRecorder {

    private final MeterRegistry meterRegistry;

    // ─────────────────────────────────────────────────────────────────
    //  ES Query Duration (Timer / Histogram)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Record the duration of an Elasticsearch query.
     *
     * @param index     the ES index or alias queried (e.g. "products", "categories", "search_suggestions")
     * @param queryType the type of query (e.g. "search", "suggest", "facet", "get_by_id")
     * @param millis    duration in milliseconds
     */
    public void recordEsQueryDuration(String index, String queryType, long millis) {
        Timer.builder("search.es.query.duration")
            .tag("index", index)
            .tag("query_type", queryType)
            .register(meterRegistry)
            .record(millis, TimeUnit.MILLISECONDS);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Indexing Success / Failure (Counter)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Increment the indexing success counter for the given event type.
     *
     * @param eventType the event type that was successfully indexed (e.g. "PRODUCT_CREATED")
     */
    public void incrementIndexingSuccess(String eventType) {
        Counter.builder("search.indexing.total")
            .tag("event_type", eventType)
            .tag("status", "success")
            .register(meterRegistry)
            .increment();
    }

    /**
     * Increment the indexing failure counter for the given event type and error.
     *
     * @param eventType the event type that failed to index
     * @param errorType a short error category (e.g. "document_missing", "mapping_conflict", "timeout")
     */
    public void incrementIndexingFailure(String eventType, String errorType) {
        Counter.builder("search.indexing.total")
            .tag("event_type", eventType)
            .tag("status", "fail")
            .tag("error_type", errorType)
            .register(meterRegistry)
            .increment();
    }

    // ─────────────────────────────────────────────────────────────────
    //  ES Errors (Counter)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Increment the ES error counter.
     *
     * @param errorType the category of error (e.g. "timeout", "5xx", "4xx", "connection_refused")
     */
    public void incrementEsError(String errorType) {
        Counter.builder("search.es.error.total")
            .tag("error_type", errorType)
            .register(meterRegistry)
            .increment();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Event Ignored (Counter)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Increment the counter for events that were silently ignored.
     *
     * @param topic     the Kafka topic the event came from
     * @param eventType the event type (or "null" / "unknown" / "disallowed")
     */
    public void incrementEventIgnored(String topic, String eventType) {
        Counter.builder("search.event.ignored.total")
            .tag("topic", topic)
            .tag("event_type", eventType)
            .register(meterRegistry)
            .increment();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Document Missing (Counter)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Increment the counter for document_missing_exception occurrences.
     *
     * @param topic     the Kafka topic the event came from
     * @param eventType the event type
     * @param docId     the document ID that was not found (e.g. sku_id or category_id)
     */
    public void incrementDocumentMissing(String topic, String eventType, String docId) {
        Counter.builder("search.document.missing.total")
            .tag("topic", topic)
            .tag("event_type", eventType)
            .tag("doc_id", docId)
            .register(meterRegistry)
            .increment();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Payload Invalid (Counter)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Increment the counter for events with invalid/malformed payloads.
     *
     * @param topic     the Kafka topic the event came from
     * @param eventType the event type
     */
    public void incrementPayloadInvalid(String topic, String eventType) {
        Counter.builder("search.payload.invalid.total")
            .tag("topic", topic)
            .tag("event_type", eventType)
            .register(meterRegistry)
            .increment();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Category Enrichment Miss (Counter)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Increment the counter for category lookup misses during event enrichment.
     *
     * @param topic      the Kafka topic the event came from
     * @param eventType  the event type
     * @param categoryId the category ID that was not found
     */
    public void incrementCategoryEnrichmentMiss(String topic, String eventType, String categoryId) {
        Counter.builder("search.category.enrichment.miss.total")
            .tag("topic", topic)
            .tag("event_type", eventType)
            .tag("category_id", categoryId)
            .register(meterRegistry)
            .increment();
    }
}
