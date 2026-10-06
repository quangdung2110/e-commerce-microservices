package com.ecommerce.review.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code reviews}.
 *
 * <p>{@code productId}/{@code skuId} are a denormalisation the composite foreign key
 * verifies against the entitlement (REVIEW-INV-003), so the public read path never
 * joins to authorize a product filter. There is no owner column here: ownership is
 * resolved through the eligibility, which keeps the hard purge to one place.
 */
public record Review(
        UUID id,
        UUID eligibilityId,
        String productId,
        String skuId,
        String authorDisplayName,
        Instant authorAnonymizedAt,
        int rating,
        String content,
        int editCount,
        ReviewStatus status,
        int activeReportCount,
        boolean hasMedia,
        Instant firstReportedAt,
        Instant hiddenAt,
        Instant createdAt,
        Instant updatedAt) {
}
