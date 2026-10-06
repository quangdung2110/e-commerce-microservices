package com.ecommerce.review.service;

import com.ecommerce.review.common.Cursors;
import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.domain.AdminReply;
import com.ecommerce.review.domain.AttachedMedia;
import com.ecommerce.review.domain.Eligibility;
import com.ecommerce.review.domain.EligibilityStatus;
import com.ecommerce.review.domain.OwnedReview;
import com.ecommerce.review.domain.RatingAggregate;
import com.ecommerce.review.domain.Review;
import com.ecommerce.review.domain.ReviewWithVariant;
import com.ecommerce.review.dto.InternalDtos;
import com.ecommerce.review.dto.PublicDtos;
import com.ecommerce.review.repository.AdminReplyRepository;
import com.ecommerce.review.repository.EligibilityRepository;
import com.ecommerce.review.repository.RatingAggregateRepository;
import com.ecommerce.review.repository.ReviewMediaRepository;
import com.ecommerce.review.repository.ReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WF-REVIEW-005 and the PDP half of WF-REVIEW-012: every read path.
 *
 * <p>Reads go straight to PostgreSQL. There is no cache: an author must see their own
 * review immediately, hidden content has to disappear immediately, and a stale rating is
 * a mistake users see directly (ADR-002).
 */
@Service
@Transactional(readOnly = true)
public class ReviewQueryService {

    private final ReviewRepository reviews;
    private final EligibilityRepository eligibilities;
    private final ReviewMediaRepository reviewMedia;
    private final AdminReplyRepository adminReplies;
    private final RatingAggregateRepository aggregates;
    private final ReviewAssembler assembler;

    public ReviewQueryService(ReviewRepository reviews,
                              EligibilityRepository eligibilities,
                              ReviewMediaRepository reviewMedia,
                              AdminReplyRepository adminReplies,
                              RatingAggregateRepository aggregates,
                              ReviewAssembler assembler) {
        this.reviews = reviews;
        this.eligibilities = eligibilities;
        this.reviewMedia = reviewMedia;
        this.adminReplies = adminReplies;
        this.aggregates = aggregates;
        this.assembler = assembler;
    }

    /**
     * The public product listing.
     *
     * <p>A product Review has never heard of and a product with no review both answer an
     * empty page: Review does not verify product identifiers against Catalog on the read
     * path, so "unknown" is not a distinguishable state here.
     */
    public PublicDtos.PublicReviewPage listProductReviews(String productId,
                                                          String skuId,
                                                          Integer rating,
                                                          boolean hasMediaOnly,
                                                          String cursor,
                                                          int limit) {
        Cursors.Position position = Cursors.decode(cursor);
        List<ReviewWithVariant> rows = reviews.listApproved(productId, skuId, rating, hasMediaOnly,
                position == null ? null : position.timestamp(),
                position == null ? null : position.id(),
                limit);

        List<UUID> ids = rows.stream().map(row -> row.review().id()).toList();
        Map<UUID, List<AttachedMedia>> media = reviewMedia.loadByReviewIds(ids);
        Map<UUID, AdminReply> replies = adminReplies.loadByReviewIds(ids);

        List<PublicDtos.PublicReview> items = rows.stream()
                .map(row -> assembler.toPublicReview(row.review(), row.variantName(),
                        ReviewAssembler.mediaOf(media, row.review().id()),
                        replies.get(row.review().id())))
                .toList();

        return new PublicDtos.PublicReviewPage(items, nextCursor(rows, limit));
    }

    /** Zero when there is no aggregate row; a store failure answers 503 instead. */
    public PublicDtos.ProductReviewAggregate getAggregate(String productId) {
        return assembler.toAggregate(aggregates.find(productId)
                .orElseGet(() -> RatingAggregate.zero(productId)));
    }

    public PublicDtos.PendingEligibilityPage listPending(UUID callerId, String cursor, int limit) {
        Cursors.Position position = Cursors.decode(cursor);
        List<Eligibility> rows = eligibilities.listPending(callerId,
                position == null ? null : position.timestamp(),
                position == null ? null : position.id(),
                limit);

        List<PublicDtos.PendingEligibility> items = rows.stream()
                .map(row -> new PublicDtos.PendingEligibility(row.id(), row.orderId(), row.productId(),
                        row.skuId(), row.variantNameSnapshot(), row.productNameSnapshot(),
                        row.productImageUrlSnapshot(), row.expiresAt()))
                .toList();

        String next = rows.size() < limit ? null
                : Cursors.encode(rows.get(rows.size() - 1).expiresAt(), rows.get(rows.size() - 1).id());
        return new PublicDtos.PendingEligibilityPage(items, next);
    }

    /**
     * The buyer's own reviews, newest first, whatever their visibility.
     *
     * <p>Like the single owner read it issues no signed media URL for a review that is not
     * public and exposes no report, legal record or takedown reason.
     */
    public PublicDtos.OwnerReviewPage listMyReviews(UUID callerId, String cursor, int limit) {
        Cursors.Position position = Cursors.decode(cursor);
        List<OwnedReview> rows = reviews.listOwned(callerId,
                position == null ? null : position.timestamp(),
                position == null ? null : position.id(),
                limit);

        List<UUID> ids = rows.stream().map(row -> row.review().id()).toList();
        Map<UUID, List<AttachedMedia>> media = reviewMedia.loadByReviewIds(ids);
        Map<UUID, AdminReply> replies = adminReplies.loadByReviewIds(ids);

        List<PublicDtos.OwnerReview> items = rows.stream()
                .map(row -> assembler.toOwnerReview(row,
                        ReviewAssembler.mediaOf(media, row.review().id()),
                        replies.get(row.review().id())))
                .toList();

        String next = rows.size() < limit ? null
                : Cursors.encode(rows.get(rows.size() - 1).review().createdAt(),
                        rows.get(rows.size() - 1).review().id());
        return new PublicDtos.OwnerReviewPage(items, next);
    }

    /**
     * The recovery read after a create whose response was lost.
     *
     * <p>An entitlement whose owner was purged answers {@code NOT_ELIGIBILITY_OWNER}
     * like any other entitlement of a different account: after the purge there is no
     * identifier left to match the caller against.
     */
    public PublicDtos.MyEligibilityReviewStatus getMyEligibilityStatus(UUID callerId, UUID eligibilityId) {
        Eligibility eligibility = eligibilities.findById(eligibilityId)
                .orElseThrow(() -> new ReviewException(ErrorCode.ELIGIBILITY_NOT_FOUND,
                        "Entitlement does not exist"));
        if (!callerId.equals(eligibility.ownerUserId())) {
            throw new ReviewException(ErrorCode.NOT_ELIGIBILITY_OWNER,
                    "The entitlement belongs to another account");
        }
        UUID reviewId = eligibility.status() == EligibilityStatus.CLAIMED
                ? reviews.findIdByEligibilityId(eligibilityId).orElse(null)
                : null;
        return new PublicDtos.MyEligibilityReviewStatus(eligibility.id(), eligibility.status(),
                eligibility.expiresAt(), reviewId);
    }

    /**
     * The recovery read after an edit whose response was lost.
     *
     * <p>It shows the owner their own review even when it is hidden or removed, but
     * issues no signed media URL for it and exposes no report, legal record or takedown
     * reason.
     */
    public PublicDtos.OwnerReview getMyReview(UUID callerId, UUID reviewId) {
        OwnedReview row = reviews.findOwned(reviewId)
                .orElseThrow(() -> new ReviewException(ErrorCode.REVIEW_NOT_FOUND, "Review does not exist"));
        Eligibility eligibility = eligibilities.findByReviewId(reviewId)
                .orElseThrow(() -> new ReviewException(ErrorCode.REVIEW_NOT_FOUND, "Review does not exist"));
        if (!callerId.equals(eligibility.ownerUserId())) {
            throw new ReviewException(ErrorCode.NOT_REVIEW_OWNER, "The review belongs to another account");
        }
        return assembler.toOwnerReview(row,
                reviewMedia.loadByReviewIds(List.of(reviewId)).getOrDefault(reviewId, List.of()),
                adminReplies.findByReviewId(reviewId).orElse(null));
    }

    /** The just-committed command response, assembled from what the command returned. */
    public PublicDtos.OwnerReview toOwnerReview(ReviewCommandService.CommandResult result) {
        Review review = result.review();
        Eligibility eligibility = result.eligibility();
        OwnedReview owned = new OwnedReview(review, eligibility.variantNameSnapshot(),
                eligibility.productNameSnapshot(), eligibility.productImageUrlSnapshot());
        return assembler.toOwnerReview(owned, result.media(),
                adminReplies.findByReviewId(review.id()).orElse(null));
    }

    /**
     * The Product Detail BFF read: the aggregate plus a bounded approved preview.
     *
     * <p>No eligibility, report, legal state or review revision crosses this boundary.
     */
    public InternalDtos.ProductReviewPdpSummary pdpSummary(String productId, int previewLimit) {
        List<InternalDtos.ProductReviewPreviewItem> preview = reviews.previewApproved(productId, previewLimit)
                .stream()
                .map(row -> assembler.toPreviewItem(row.review(), row.variantName()))
                .toList();
        return new InternalDtos.ProductReviewPdpSummary(productId, getAggregate(productId), preview);
    }

    private static String nextCursor(List<ReviewWithVariant> rows, int limit) {
        if (rows.size() < limit) {
            return null;
        }
        Review last = rows.get(rows.size() - 1).review();
        return Cursors.encode(last.createdAt(), last.id());
    }
}
