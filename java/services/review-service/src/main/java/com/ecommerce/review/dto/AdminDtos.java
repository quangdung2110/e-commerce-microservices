package com.ecommerce.review.dto;

import com.ecommerce.review.domain.MediaType;
import com.ecommerce.review.domain.ReportReason;
import com.ecommerce.review.domain.ReportStatus;
import com.ecommerce.review.domain.ReviewStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The admin moderation surface.
 *
 * <p>Its unit is the review, not the report: three reports on one review are one case,
 * and {@code report-dismissals} acts on the review. Reporter identity appears nowhere.
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /**
     * Media of a review that may be hidden.
     *
     * <p>This is the one deliberate exception to "no signed URL for non-approved media":
     * a report about an image or a video cannot be adjudicated without seeing it. It is
     * scoped to {@code /api/v1/admin/**} and its TTL is shorter than the public one
     * ({@code docs/review/interfaces.md 2}).
     */
    public record AdminReviewMedia(
            UUID assetId,
            MediaType mediaType,
            String url,
            Instant urlExpiresAt,
            int sortOrder) {
    }

    public record AdminReviewSnapshot(
            UUID reviewId,
            String productId,
            String skuId,
            int rating,
            String content,
            boolean hasMedia,
            List<AdminReviewMedia> media,
            ReviewStatus visibilityStatus,
            int activeReportCount,
            Instant hiddenAt,
            Instant firstReportedAt,
            Instant createdAt) {
    }

    /** No {@code reporter_user_id}: the administrator judges the report, not the reporter. */
    public record AdminModerationCaseReport(
            UUID reportId,
            ReportReason reason,
            String detail,
            ReportStatus status,
            Instant createdAt) {
    }

    /**
     * One case with everything needed to decide in a single read.
     *
     * <p>{@code hiddenAt} is null only when the review has never been hidden. It is
     * deliberately retained across a dismissal that restored the review, so a repeatedly
     * brigaded review keeps its history and a {@code REPORTED} case can legitimately
     * carry a past hide ({@code docs/review/data.md 5}).
     */
    public record AdminModerationCase(
            AdminReviewSnapshot review,
            List<AdminModerationCaseReport> reports,
            Instant firstReportedAt,
            Instant hiddenAt) {
    }

    public record AdminModerationCasePage(List<AdminModerationCase> items, String nextCursor) {
    }

    /** The dismissal result. Only an administrator ever sees these counters. */
    public record AdminModerationResult(
            UUID reviewId,
            ReviewStatus visibilityStatus,
            int dismissedReportCount,
            int activeReportCount) {
    }
}
