package com.ecommerce.review.outbox;

import java.util.Map;

/**
 * The eight message types of {@code t.review.events}.
 *
 * <p>Each one pins its CloudEvents {@code type}, the JSON Schema path that becomes
 * {@code dataschema}, and the {@code aggregate_type} projection. {@code aggregate_type}
 * is metadata only: the connector pins a static {@code route.topic.replacement} so every
 * row lands on the one topic, keyed by {@code product_id} (ADR-003).
 */
public enum ReviewEventType {

    REVIEW_CREATED("com.ecommerce.review.created.v1", "review-created", AggregateType.REVIEW),
    REVIEW_UPDATED("com.ecommerce.review.updated.v1", "review-updated", AggregateType.REVIEW),
    REVIEW_HIDDEN("com.ecommerce.review.hidden.v1", "review-hidden", AggregateType.REVIEW),
    REVIEW_APPROVED("com.ecommerce.review.approved.v1", "review-approved", AggregateType.REVIEW),
    REVIEW_REMOVED("com.ecommerce.review.removed.v1", "review-removed", AggregateType.REVIEW),
    REVIEW_METRICS_UPDATED("com.ecommerce.review.metrics-updated.v1", "review-metrics-updated",
            AggregateType.REVIEW_METRIC),
    REVIEW_REPLY_NOTIFICATION_REQUESTED("com.ecommerce.review.reply-notification-requested.v1",
            "review-reply-notification-requested", AggregateType.REVIEW),
    REVIEW_REMOVAL_NOTIFICATION_REQUESTED("com.ecommerce.review.removal-notification-requested.v1",
            "review-removal-notification-requested", AggregateType.REVIEW);

    /** The only two {@code review_outbox.aggregate_type} values the CHECK allows. */
    public enum AggregateType {
        /** {@code aggregate_id = review_id}: the five facts and both notification requests. */
        REVIEW,
        /** {@code aggregate_id = product_id}: the rating snapshot. */
        REVIEW_METRIC
    }

    private static final String SCHEMA_PREFIX = "/contracts/events/review/v1/schemas/";

    private static final Map<String, ReviewEventType> BY_TYPE = Map.of(
            REVIEW_CREATED.type, REVIEW_CREATED,
            REVIEW_UPDATED.type, REVIEW_UPDATED,
            REVIEW_HIDDEN.type, REVIEW_HIDDEN,
            REVIEW_APPROVED.type, REVIEW_APPROVED,
            REVIEW_REMOVED.type, REVIEW_REMOVED,
            REVIEW_METRICS_UPDATED.type, REVIEW_METRICS_UPDATED,
            REVIEW_REPLY_NOTIFICATION_REQUESTED.type, REVIEW_REPLY_NOTIFICATION_REQUESTED,
            REVIEW_REMOVAL_NOTIFICATION_REQUESTED.type, REVIEW_REMOVAL_NOTIFICATION_REQUESTED);

    private final String type;
    private final String schemaName;
    private final AggregateType aggregateType;

    ReviewEventType(String type, String schemaName, AggregateType aggregateType) {
        this.type = type;
        this.schemaName = schemaName;
        this.aggregateType = aggregateType;
    }

    public String type() {
        return type;
    }

    public String dataschema() {
        return SCHEMA_PREFIX + schemaName + ".schema.json";
    }

    public String schemaFileName() {
        return schemaName + ".schema.json";
    }

    public AggregateType aggregateType() {
        return aggregateType;
    }

    /**
     * The five facts the rating aggregator self-consumes. Everything else on the topic,
     * {@link #REVIEW_METRICS_UPDATED} included, is acknowledged without an effect so the
     * aggregator cannot feed itself (WF-REVIEW-008).
     */
    public boolean affectsRatingAggregate() {
        return this == REVIEW_CREATED || this == REVIEW_UPDATED || this == REVIEW_HIDDEN
                || this == REVIEW_APPROVED || this == REVIEW_REMOVED;
    }

    /** Null for a type Review does not own; consumers ACK those without an effect. */
    public static ReviewEventType fromType(String type) {
        return type == null ? null : BY_TYPE.get(type);
    }
}
