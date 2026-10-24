package com.ecommerce.review.service;

import com.ecommerce.review.config.ReviewProperties;
import com.ecommerce.review.domain.AdminReply;
import com.ecommerce.review.domain.AttachedMedia;
import com.ecommerce.review.domain.OwnedReview;
import com.ecommerce.review.domain.RatingAggregate;
import com.ecommerce.review.domain.Report;
import com.ecommerce.review.domain.Review;
import com.ecommerce.review.domain.ReviewStatus;
import com.ecommerce.review.dto.AdminDtos;
import com.ecommerce.review.dto.InternalDtos;
import com.ecommerce.review.dto.PublicDtos;
import com.ecommerce.review.storage.ReviewObjectStorage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Turns domain rows into contract responses, and is the only place a signed media URL
 * is produced.
 *
 * <p>Two rules live here rather than in each caller. Public and owner reads issue no URL
 * at all for the media of a review that is not {@code APPROVED}. The admin moderation
 * surface does issue one even for a hidden review — a report about an image cannot be
 * judged without seeing it — with a strictly shorter TTL (WF-REVIEW-005).
 */
@Component
public class ReviewAssembler {

    private final ReviewObjectStorage storage;
    private final ReviewProperties properties;

    public ReviewAssembler(ReviewObjectStorage storage, ReviewProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    public PublicDtos.PublicReview toPublicReview(Review review,
                                                  String variantName,
                                                  List<AttachedMedia> media,
                                                  AdminReply reply) {
        return new PublicDtos.PublicReview(
                review.id(),
                review.productId(),
                review.skuId(),
                review.authorDisplayName(),
                variantName,
                review.rating(),
                review.content(),
                review.hasMedia(),
                signPublicMedia(review.status(), media),
                toOfficialReply(reply),
                review.createdAt(),
                review.updatedAt());
    }

    public PublicDtos.OwnerReview toOwnerReview(OwnedReview owned,
                                                List<AttachedMedia> media,
                                                AdminReply reply) {
        Review review = owned.review();
        return new PublicDtos.OwnerReview(
                review.id(),
                review.productId(),
                review.skuId(),
                review.authorDisplayName(),
                owned.variantName(),
                owned.productName(),
                owned.productImageUrl(),
                review.rating(),
                review.content(),
                review.hasMedia(),
                signPublicMedia(review.status(), media),
                toOfficialReply(reply),
                review.status(),
                review.editCount(),
                review.createdAt(),
                review.updatedAt(),
                review.contentEditedAt());
    }

    public PublicDtos.OfficialReply toOfficialReply(AdminReply reply) {
        return reply == null ? null
                : new PublicDtos.OfficialReply(reply.content(), reply.createdAt(), reply.updatedAt());
    }

    public PublicDtos.ProductReviewAggregate toAggregate(RatingAggregate aggregate) {
        return new PublicDtos.ProductReviewAggregate(
                aggregate.averageRating(), aggregate.totalReviews(), aggregate.totalWithMedia());
    }

    public InternalDtos.ProductReviewPreviewItem toPreviewItem(Review review, String variantName) {
        return new InternalDtos.ProductReviewPreviewItem(
                review.id(),
                review.rating(),
                review.content(),
                review.authorDisplayName(),
                variantName,
                review.hasMedia(),
                review.createdAt());
    }

    public InternalDtos.RatingAggregateSnapshot toExportSnapshot(RatingAggregate aggregate) {
        return new InternalDtos.RatingAggregateSnapshot(
                aggregate.productId(),
                aggregate.averageRating(),
                aggregate.totalReviews(),
                aggregate.totalWithMedia(),
                aggregate.reviewRevision(),
                aggregate.updatedAt());
    }

    public AdminDtos.AdminModerationCase toModerationCase(Review review,
                                                          List<AttachedMedia> media,
                                                          List<Report> reports) {
        AdminDtos.AdminReviewSnapshot snapshot = new AdminDtos.AdminReviewSnapshot(
                review.id(),
                review.productId(),
                review.skuId(),
                review.rating(),
                review.content(),
                review.hasMedia(),
                signAdminMedia(media),
                review.status(),
                review.activeReportCount(),
                review.hiddenAt(),
                review.firstReportedAt(),
                review.createdAt());

        List<AdminDtos.AdminModerationCaseReport> caseReports = reports.stream()
                // reporter_user_id and reporter_erased_at are dropped here and appear on
                // no administrator surface (WF-REVIEW-007).
                .map(report -> new AdminDtos.AdminModerationCaseReport(
                        report.id(), report.reason(), report.detail(), report.status(), report.createdAt()))
                .toList();

        return new AdminDtos.AdminModerationCase(snapshot, caseReports,
                review.firstReportedAt(), review.hiddenAt());
    }

    private List<PublicDtos.PublicReviewMedia> signPublicMedia(ReviewStatus status,
                                                               List<AttachedMedia> media) {
        if (status != ReviewStatus.APPROVED || media == null || media.isEmpty()) {
            return List.of();
        }
        Duration ttl = properties.media().publicUrlTtl();
        Instant expiresAt = Instant.now().plus(ttl);
        return media.stream()
                .map(item -> new PublicDtos.PublicReviewMedia(
                        item.assetId(), item.mediaType(),
                        storage.presignDownload(item.objectKey(), ttl), expiresAt, item.sortOrder()))
                .toList();
    }

    private List<AdminDtos.AdminReviewMedia> signAdminMedia(List<AttachedMedia> media) {
        if (media == null || media.isEmpty()) {
            return List.of();
        }
        Duration ttl = properties.media().adminUrlTtl();
        Instant expiresAt = Instant.now().plus(ttl);
        return media.stream()
                .map(item -> new AdminDtos.AdminReviewMedia(
                        item.assetId(), item.mediaType(),
                        storage.presignDownload(item.objectKey(), ttl), expiresAt, item.sortOrder()))
                .toList();
    }

    /** Media of a review that is on a page; empty list when it has none. */
    public static List<AttachedMedia> mediaOf(Map<java.util.UUID, List<AttachedMedia>> byReview,
                                              java.util.UUID reviewId) {
        return byReview.getOrDefault(reviewId, List.of());
    }
}
