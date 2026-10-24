package com.ecommerce.review.dto;

import com.ecommerce.review.domain.MediaType;
import com.ecommerce.review.domain.ReviewStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The public and buyer response bodies of
 * {@code contracts/http/review/v1/openapi.yaml}.
 *
 * <p>Every one of these is {@code additionalProperties: false} in the contract, so a
 * field added here without a contract change is a breaking change. Several are required
 * <em>and</em> nullable, which is why the HTTP mapper serialises nulls rather than
 * omitting them.
 */
public final class PublicDtos {

    private PublicDtos() {
    }

    /**
     * A signed download URL that exists only inside this response.
     *
     * <p>Public and owner reads issue one only for the media of an {@code APPROVED}
     * review; the moderation surface is the single deliberate exception (WF-REVIEW-005).
     */
    public record PublicReviewMedia(
            UUID assetId,
            MediaType mediaType,
            String url,
            Instant urlExpiresAt,
            int sortOrder) {
    }

    public record OfficialReply(String content, Instant createdAt, Instant updatedAt) {
    }

    public record PublicReview(
            UUID reviewId,
            String productId,
            String skuId,
            String authorName,
            String variantName,
            int rating,
            String content,
            boolean hasMedia,
            List<PublicReviewMedia> media,
            OfficialReply adminReply,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * The owner view. It adds {@code visibility_status}, {@code edit_count}, the
     * nullable author-only {@code content_edited_at}, and the
     * product name and thumbnail snapshotted at purchase, and nothing else: reports, reporter identity, the legal record and the takedown reason
     * are not owner-visible ({@code docs/review/interfaces.md 2}).
     */
    public record OwnerReview(
            UUID reviewId,
            String productId,
            String skuId,
            String authorName,
            String variantName,
            String productName,
            String productImageUrl,
            int rating,
            String content,
            boolean hasMedia,
            List<PublicReviewMedia> media,
            OfficialReply adminReply,
            ReviewStatus visibilityStatus,
            int editCount,
            Instant createdAt,
            Instant updatedAt,
            Instant contentEditedAt) {
    }

    public record PublicReviewPage(List<PublicReview> items, String nextCursor) {
    }

    /** Zero values when the product has no aggregate row; that is not an error. */
    public record ProductReviewAggregate(
            BigDecimal averageRating,
            long totalReviews,
            long totalWithMedia) {
    }

    public record PendingEligibility(
            UUID eligibilityId,
            String orderId,
            String productId,
            String skuId,
            String variantName,
            String productName,
            String productImageUrl,
            Instant expiresAt) {
    }

    public record PendingEligibilityPage(List<PendingEligibility> items, String nextCursor) {
    }

    public record OwnerReviewPage(List<OwnerReview> items, String nextCursor) {
    }

    /**
     * The recovery read for a create response lost after commit: a {@code CLAIMED}
     * entitlement answers with its review id, so the client resolves the outcome instead
     * of retrying the command blindly.
     */
    public record MyEligibilityReviewStatus(
            UUID eligibilityId,
            com.ecommerce.review.domain.EligibilityStatus status,
            Instant expiresAt,
            UUID reviewId) {
    }
}
