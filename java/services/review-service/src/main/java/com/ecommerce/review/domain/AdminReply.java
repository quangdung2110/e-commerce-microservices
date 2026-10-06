package com.ecommerce.review.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The single official reply of a review; {@code review_admin_replies}.
 *
 * <p>{@code createdByAdminId} is preserved across every later edit, so the record keeps
 * saying who opened the reply as well as who last changed it (WF-REVIEW-006). Review
 * carries no denormalised reply flag: the public read already batch-loads the content.
 */
public record AdminReply(
        UUID id,
        UUID reviewId,
        UUID createdByAdminId,
        UUID updatedByAdminId,
        String content,
        Instant createdAt,
        Instant updatedAt) {
}
