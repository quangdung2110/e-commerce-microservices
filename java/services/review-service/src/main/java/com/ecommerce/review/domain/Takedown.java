package com.ecommerce.review.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The immutable legal audit record; {@code review_takedowns}.
 *
 * <p>One acting admin, read from trusted Gateway identity rather than the request body
 * (ADR-010). Neither {@code legalCaseId}, {@code reason} nor {@code takenDownByAdminId}
 * may appear in a public response or in either Review event.
 */
public record Takedown(
        UUID id,
        UUID reviewId,
        String legalCaseId,
        UUID takenDownByAdminId,
        String reason,
        Instant createdAt) {
}
