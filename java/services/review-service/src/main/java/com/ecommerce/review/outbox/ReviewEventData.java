package com.ecommerce.review.outbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The {@code data} bodies of the eight Review messages, exactly as the JSON Schemas
 * under {@code contracts/events/review/v1/schemas} define them.
 *
 * <p>Every one of them carries {@code product_id}: that is what lets
 * {@code ck_outbox_columns_mirror_payload} turn "the Kafka key is always the product"
 * into a database constraint (ADR-003).
 *
 * <p>What is absent is as contractual as what is present. No lifecycle fact carries
 * content, an author name or an object key; {@code REVIEW_REMOVED} carries no legal
 * case, reason or admin identity; and both notification requests carry three identifiers
 * and nothing else.
 */
public final class ReviewEventData {

    private ReviewEventData() {
    }

    /** {@code REVIEW_CREATED}, {@code REVIEW_HIDDEN} and {@code REVIEW_APPROVED}. */
    public record Lifecycle(
            UUID reviewId,
            String productId,
            String skuId,
            int rating,
            boolean hasMedia) {
    }

    /**
     * {@code REVIEW_UPDATED}. {@code affectsAggregate} is false for a content-only edit
     * and for a media replacement that keeps {@code hasMedia} true; the aggregator then
     * acknowledges it without applying a delta ({@code docs/review/interfaces.md 4}).
     */
    public record Updated(
            UUID reviewId,
            String productId,
            String skuId,
            int oldRating,
            int newRating,
            boolean oldHasMedia,
            boolean newHasMedia,
            boolean affectsAggregate) {
    }

    /**
     * {@code REVIEW_REMOVED}. {@code wasCounted} is true only when the review was
     * {@code APPROVED} immediately before removal, which is what tells the aggregator
     * whether there is anything to subtract.
     */
    public record Removed(
            UUID reviewId,
            String productId,
            String skuId,
            int rating,
            boolean hasMedia,
            boolean wasCounted) {
    }

    /** {@code REVIEW_METRICS_UPDATED}: the complete Search-facing snapshot. */
    public record Metrics(
            String productId,
            BigDecimal averageRating,
            long totalReviews,
            long totalWithMedia,
            long reviewRevision,
            Instant observedAt) {
    }

    /**
     * Both notification requests. Review writes one only when the eligibility owner has
     * no HMAC deletion guard; a guarded admin command still commits without this row
     * (REVIEW-INV-012).
     */
    public record Notification(
            UUID reviewId,
            String productId,
            UUID recipientUserId) {
    }
}
