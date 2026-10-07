package com.ecommerce.review.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The meters {@code docs/review/operations.md 4} names.
 *
 * <p>Every label here is a bounded, enumerable value. {@code product_id},
 * {@code user_id}, {@code review_id} and {@code session_id} are never labels: they would
 * make cardinality unbounded and would publish identifiers into the metrics pipeline.
 * The privacy subject key is not a label either.
 */
@Component
public class ReviewMetrics {

    private final MeterRegistry registry;

    private final AtomicLong exportSessionsActive = new AtomicLong();
    private final AtomicLong mediaPendingDelete = new AtomicLong();
    private final AtomicLong privacyOverdue = new AtomicLong();
    private final AtomicLong outboxPartitionsMissing = new AtomicLong();

    public ReviewMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("review_export_session_active", exportSessionsActive, AtomicLong::get);
        registry.gauge("review_media_asset_pending_delete_total", mediaPendingDelete, AtomicLong::get);
        registry.gauge("review_privacy_hard_delete_overdue_total", privacyOverdue, AtomicLong::get);
        registry.gauge("review_outbox_partition_missing", outboxPartitionsMissing, AtomicLong::get);
    }

    /** A receipt conflict: expected under at-least-once delivery, not an error. */
    public void consumerDuplicate(String consumer) {
        counter("review_consumer_duplicate_total", "consumer", consumer).increment();
    }

    /** Reconciliation found real drift. Duplicates no longer cause it, so this is a warning. */
    public void aggregateDrift() {
        counter("review_aggregate_drift_total").increment();
    }

    /**
     * A deletion guard suppressed a late Order event, rejected a customer write, or
     * suppressed the notification branch of an admin reply or takedown.
     *
     * @param operation a bounded operation name, never a user identifier
     */
    public void privacyGuardBlocked(String operation) {
        counter("review_privacy_guard_blocked_total", "operation", operation).increment();
    }

    public void exportSession(String result) {
        counter("review_export_session_total", "result", result).increment();
    }

    public void exportSessionsActive(long value) {
        exportSessionsActive.set(value);
    }

    public void mediaPendingDelete(long value) {
        mediaPendingDelete.set(value);
    }

    public void privacyOverdue(long value) {
        privacyOverdue.set(value);
    }

    public void outboxPartitionsMissing(long value) {
        outboxPartitionsMissing.set(value);
    }

    /**
     * @param reason one of {@code UNATTACHED_EXPIRED}, {@code REPLACED}, {@code TAKEDOWN}
     * @param result includes {@code object_not_found_success}, which is a success
     */
    public void mediaCleanup(String reason, String result) {
        counter("review_media_cleanup_total", "reason", reason, "result", result).increment();
    }

    public void mediaVerification(String result, String mediaType) {
        counter("review_media_verification_total", "result", result, "media_type", mediaType).increment();
    }

    public void mediaQuotaRejected() {
        counter("review_media_unattached_quota_rejected_total").increment();
    }

    /**
     * @param result {@code written} or {@code suppressed_by_guard}; a suppression is a
     *               deliberate outcome, not a failure
     */
    public void notificationRequest(String type, String result) {
        counter("review_notification_request_total", "type", type, "result", result).increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }
}
