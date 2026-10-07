package com.ecommerce.review.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The internal responses of {@code contracts/http/review/internal/v1/openapi.yaml}:
 * the PDP summary for Product Detail BFF and the rating export for Search.
 */
public final class InternalDtos {

    private InternalDtos() {
    }

    /**
     * The BFF read.
     *
     * <p>A product with no review answers a zero aggregate and an empty preview; a store
     * failure answers 503. The two must stay distinguishable, or the BFF cannot tell an
     * empty review section from a broken one (WF-REVIEW-012).
     */
    public record ProductReviewPdpSummary(
            String productId,
            PublicDtos.ProductReviewAggregate aggregate,
            List<ProductReviewPreviewItem> preview) {
    }

    /** No eligibility, report, legal state or review revision reaches the BFF. */
    public record ProductReviewPreviewItem(
            UUID reviewId,
            int rating,
            String content,
            String authorName,
            String variantName,
            boolean hasMedia,
            Instant createdAt) {
    }

    /** The first offset Search must replay from, inclusive. */
    public record ReplayOffset(String topic, int partition, long offset) {
    }

    public record RatingAggregateExportSession(
            UUID sessionId,
            Instant expiresAt,
            List<ReplayOffset> replayFromOffsets) {
    }

    /**
     * One page of a session.
     *
     * <p>Every page echoes the offsets captured when the session was created, even
     * though a later page may see a mutation newer than that watermark. That is safe:
     * a mutation visible in the snapshot and in the replay is deduplicated by
     * {@code review_revision}, and one not visible in the snapshot has an event at or
     * after the captured offset (ADR-006).
     */
    public record RatingAggregateExportPage(
            UUID sessionId,
            List<RatingAggregateSnapshot> items,
            String nextAfterProductId,
            List<ReplayOffset> replayFromOffsets) {
    }

    public record RatingAggregateSnapshot(
            String productId,
            BigDecimal averageRating,
            long totalReviews,
            long totalWithMedia,
            long reviewRevision,
            Instant observedAt) {
    }
}
