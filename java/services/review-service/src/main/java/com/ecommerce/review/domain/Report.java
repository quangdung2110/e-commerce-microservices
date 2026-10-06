package com.ecommerce.review.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code review_reports}.
 *
 * <p>{@code reporterUserId} never leaves the service: no surface, the admin moderation
 * queue included, returns reporter identity (WF-REVIEW-007).
 */
public record Report(
        UUID id,
        UUID reviewId,
        UUID reporterUserId,
        Instant reporterErasedAt,
        ReportReason reason,
        String detail,
        ReportStatus status,
        UUID resolvedByAdminId,
        Instant resolvedAt,
        Instant createdAt) {
}
