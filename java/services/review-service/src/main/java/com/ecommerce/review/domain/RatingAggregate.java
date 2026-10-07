package com.ecommerce.review.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A row of {@code product_rating_aggregates}: the asynchronous projection over
 * {@code APPROVED} reviews only.
 *
 * <p>{@code reviewRevision} is the ordering guard of that projection, not a version of
 * the review. It rises only when the aggregate really changes, never for a content-only
 * edit or an author anonymisation (ADR-004).
 */
public record RatingAggregate(
        String productId,
        long total1Star,
        long total2Star,
        long total3Star,
        long total4Star,
        long total5Star,
        long totalWithMedia,
        long totalReviews,
        BigDecimal averageRating,
        long reviewRevision,
        Instant updatedAt) {

    /**
     * What a product with no aggregate row returns. It is not a 404 and never a
     * substitute for a store failure, which answers 503 instead (WF-REVIEW-005).
     */
    public static RatingAggregate zero(String productId) {
        return new RatingAggregate(productId, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, 1, null);
    }
}
