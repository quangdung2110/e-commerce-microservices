package com.ecommerce.search.common;

import java.util.Arrays;

/**
 * All event types that Search Service can consume across its three Kafka topics.
 *
 * <p>Each entry declares which topic it arrives on and whether it is currently
 * allowed in the main consumer group. Entries that are no longer emitted
 * (e.g. {@link #PRODUCT_STATUS_CHANGED}) are kept for legacy compatibility
 * with {@code allowedInConsumerGroup = false} so the consumer can explicitly
 * ignore them with a metric counter rather than logging a warning.
 */
public enum EventType {

    PRODUCT_CREATED("t.product.events", true),
    PRODUCT_UPDATED("t.product.events", true),
    PRODUCT_STATUS_CHANGED("t.product.events", false),  // Phase 19: no longer emitted, kept for legacy
    SKU_UPDATED("t.product.events", true),
    SKU_CREATED("t.product.events", true),
    PRODUCT_DELETED("t.product.events", true),
    SKU_DELETED("t.product.events", true),
    CATEGORY_CREATED("t.category.events", true),
    CATEGORY_UPDATED("t.category.events", true),
    CATEGORY_DELETED("t.category.events", true),
    REVIEW_METRICS_UPDATED("t.review.events", true);

    private final String topic;
    private final boolean allowedInConsumerGroup;

    EventType(String topic, boolean allowedInConsumerGroup) {
        this.topic = topic;
        this.allowedInConsumerGroup = allowedInConsumerGroup;
    }

    /**
     * The canonical Kafka topic name for this event type.
     */
    public String topic() {
        return topic;
    }

    /**
     * Whether this event type is actively processed by the main consumer group.
     * {@code false} entries are silently ignored (with a metric counter bump).
     */
    public boolean isAllowedInConsumerGroup() {
        return allowedInConsumerGroup;
    }

    /**
     * Parse an event type name (e.g. {@code "PRODUCT_CREATED"}) into the
     * corresponding enum constant.
     *
     * @param name the raw event type string from the CloudEvents envelope
     * @return the matching {@code EventType}, or {@code null} if no match
     */
    public static EventType fromString(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
            .filter(e -> e.name().equals(name))
            .findFirst()
            .orElse(null);
    }
}
