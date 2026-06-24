package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Payload for {@code REVIEW_METRICS_UPDATED} events consumed from {@code t.review.events}.
 *
 * <p>Carries updated rating aggregates for a product. This event type
 * <strong>does not</strong> use script-based guard ordering (Phase 21);
 * it relies solely on Kafka partition ordering by {@code product_id}.
 *
 * <p>The {@code total_with_media} field from upstream is intentionally ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewMetricsUpdatedEvent(
    @JsonProperty("product_id") String productId,
    @JsonProperty("average_rating") Double averageRating,
    @JsonProperty("total_reviews") Integer totalReviews,
    @JsonProperty("updated_at") Instant updatedAt
) {}
