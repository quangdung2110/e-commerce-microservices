package com.ecommerce.review.service;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.config.ReviewProperties;
import com.ecommerce.review.domain.AttachedMedia;
import com.ecommerce.review.domain.Eligibility;
import com.ecommerce.review.domain.EligibilityStatus;
import com.ecommerce.review.domain.MediaDeletionReason;
import com.ecommerce.review.domain.Review;
import com.ecommerce.review.domain.ReviewStatus;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.dto.PatchReviewRequest;
import com.ecommerce.review.outbox.ReviewEventData;
import com.ecommerce.review.outbox.ReviewEventFactory;
import com.ecommerce.review.outbox.ReviewEventType;
import com.ecommerce.review.privacy.IdentityHmac;
import com.ecommerce.review.repository.EligibilityRepository;
import com.ecommerce.review.repository.MediaAssetRepository;
import com.ecommerce.review.repository.OutboxRepository;
import com.ecommerce.review.repository.ReviewMediaRepository;
import com.ecommerce.review.repository.ReviewRepository;
import com.ecommerce.review.repository.TransactionClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WF-REVIEW-003 and WF-REVIEW-004: creating a review and spending its single edit.
 *
 * <p>Both commands verify submitted media metadata <em>before</em> the transaction opens
 * and keep the domain change, the media transition, the entitlement claim and the outbox
 * row inside one transaction. Nothing external happens before commit.
 */
@Service
public class ReviewCommandService {

    private final EligibilityRepository eligibilities;
    private final ReviewRepository reviews;
    private final ReviewMediaRepository reviewMedia;
    private final MediaAssetRepository mediaAssets;
    private final MediaAssetService mediaAssetService;
    private final PrivacyGuardService privacyGuard;
    private final IdentityHmac identityHmac;
    private final OutboxRepository outbox;
    private final ReviewEventFactory events;
    private final TransactionClock clock;
    private final ReviewProperties properties;
    private final TransactionTemplate transactions;

    public ReviewCommandService(EligibilityRepository eligibilities,
                                ReviewRepository reviews,
                                ReviewMediaRepository reviewMedia,
                                MediaAssetRepository mediaAssets,
                                MediaAssetService mediaAssetService,
                                PrivacyGuardService privacyGuard,
                                IdentityHmac identityHmac,
                                OutboxRepository outbox,
                                ReviewEventFactory events,
                                TransactionClock clock,
                                ReviewProperties properties,
                                TransactionTemplate transactions) {
        this.eligibilities = eligibilities;
        this.reviews = reviews;
        this.reviewMedia = reviewMedia;
        this.mediaAssets = mediaAssets;
        this.mediaAssetService = mediaAssetService;
        this.privacyGuard = privacyGuard;
        this.identityHmac = identityHmac;
        this.outbox = outbox;
        this.events = events;
        this.clock = clock;
        this.properties = properties;
        this.transactions = transactions;
    }

    /** A committed command, with everything the response needs. */
    public record CommandResult(Review review, Eligibility eligibility, List<AttachedMedia> media) {

        public String variantName() {
            return eligibility.variantNameSnapshot();
        }
    }

    /**
     * Creates the one review of a pending entitlement.
     *
     * <p>The object metadata check runs first and outside the transaction: it is a
     * storage read, the upload is already complete, and it must not hold database locks.
     */
    public CommandResult create(UUID callerId, CommandDtos.CreateReviewRequest request) {
        validateContent(request.content());
        List<MediaAssetService.VerifiedAsset> verified =
                mediaAssetService.verifyForAttachment(callerId, request.mediaAssetIds());
        // The transaction boundary is explicit rather than annotated: the metadata read
        // above must stay outside it, and a self-invoked @Transactional method would
        // silently run with no transaction at all.
        return transactions.execute(status -> createCommitted(callerId, request, verified));
    }

    private CommandResult createCommitted(UUID callerId,
                                          CommandDtos.CreateReviewRequest request,
                                          List<MediaAssetService.VerifiedAsset> verified) {
        privacyGuard.lockAndRequireUnguarded(callerId, "review-create");
        Instant now = clock.now();

        Eligibility claimed = eligibilities.claim(request.eligibilityId(), callerId, now)
                .orElseThrow(() -> classifyFailedClaim(request.eligibilityId(), callerId, now));

        boolean hasMedia = !verified.isEmpty();
        Review review = reviews.insert(claimed.id(), claimed.productId(), claimed.skuId(),
                identityHmac.authorDisplayName(callerId), request.rating(),
                normalize(request.content()), hasMedia);

        List<UUID> attached = mediaAssetService.lockAndAttach(callerId, verified, now);
        for (int i = 0; i < attached.size(); i++) {
            reviewMedia.attach(review.id(), attached.get(i), i);
        }

        outbox.append(events.lifecycle(ReviewEventType.REVIEW_CREATED,
                new ReviewEventData.Lifecycle(review.id(), review.productId(), review.skuId(),
                        review.rating(), review.hasMedia()),
                now));

        return new CommandResult(review, claimed,
                reviewMedia.loadByReviewIds(List.of(review.id())).getOrDefault(review.id(), List.of()));
    }

    /**
     * Spends the single permitted edit.
     *
     * <p>A supplied media list replaces the attachments completely; the ones it removed
     * become {@code DELETE_PENDING} with {@code REPLACED}. An absent list keeps them, and
     * an empty list removes them all.
     */
    public CommandResult edit(UUID callerId, UUID reviewId, PatchReviewRequest request) {
        List<MediaAssetService.VerifiedAsset> verified = request.mediaAssetIds().present()
                ? mediaAssetService.verifyForAttachment(callerId, request.mediaAssetIds().value())
                : List.of();
        return transactions.execute(status -> editCommitted(callerId, reviewId, request, verified));
    }

    private CommandResult editCommitted(UUID callerId,
                                        UUID reviewId,
                                        PatchReviewRequest request,
                                        List<MediaAssetService.VerifiedAsset> verified) {
        privacyGuard.lockAndRequireUnguarded(callerId, "review-edit");
        Instant now = clock.now();

        Review current = reviews.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new ReviewException(ErrorCode.REVIEW_NOT_FOUND, "Review does not exist"));
        Eligibility eligibility = eligibilities.findByReviewId(reviewId)
                .orElseThrow(() -> new ReviewException(ErrorCode.REVIEW_NOT_FOUND, "Review does not exist"));
        if (!callerId.equals(eligibility.ownerUserId())) {
            throw new ReviewException(ErrorCode.NOT_REVIEW_OWNER, "The review belongs to another account");
        }
        if (current.status() != ReviewStatus.APPROVED) {
            throw new ReviewException(ErrorCode.REVIEW_NOT_EDITABLE,
                    "Only an approved review can be edited");
        }
        if (current.editCount() != 0) {
            throw new ReviewException(ErrorCode.EDIT_QUOTA_EXHAUSTED,
                    "The single permitted edit was already used");
        }

        int newRating = request.rating().orElse(current.rating());
        String newContent = request.content().present()
                ? normalize(request.content().value())
                : current.content();
        validateContent(newContent);

        boolean newHasMedia = current.hasMedia();
        if (request.mediaAssetIds().present()) {
            List<UUID> detached = reviewMedia.detachAll(reviewId);
            mediaAssets.queueDeletion(detached, MediaDeletionReason.REPLACED, now);
            List<UUID> attached = mediaAssetService.lockAndAttach(callerId, verified, now);
            for (int i = 0; i < attached.size(); i++) {
                reviewMedia.attach(reviewId, attached.get(i), i);
            }
            newHasMedia = !attached.isEmpty();
        }

        Review updated = reviews.applyEdit(reviewId, newRating, newContent, newHasMedia)
                .orElseThrow(() -> new ReviewException(ErrorCode.EDIT_QUOTA_EXHAUSTED,
                        "The single permitted edit was already used"));

        // A content-only edit, and a media replacement that keeps has_media true, change
        // nothing the aggregate counts (ADR-004).
        boolean affectsAggregate = current.rating() != updated.rating()
                || current.hasMedia() != updated.hasMedia();
        outbox.append(events.updated(new ReviewEventData.Updated(
                updated.id(), updated.productId(), updated.skuId(),
                current.rating(), updated.rating(),
                current.hasMedia(), updated.hasMedia(),
                affectsAggregate), now));

        return new CommandResult(updated, eligibility,
                reviewMedia.loadByReviewIds(List.of(reviewId)).getOrDefault(reviewId, List.of()));
    }

    /**
     * Turns a zero-row claim into the error that actually happened.
     *
     * <p>The conditional update deliberately checks ownership, state and expiry at once,
     * so the classification is a second read: it never widens what the command could
     * have done, only what the client is told (WF-REVIEW-003).
     */
    private ReviewException classifyFailedClaim(UUID eligibilityId, UUID callerId, Instant now) {
        Eligibility eligibility = eligibilities.findById(eligibilityId).orElse(null);
        if (eligibility == null) {
            return new ReviewException(ErrorCode.ELIGIBILITY_NOT_FOUND, "Entitlement does not exist");
        }
        if (!callerId.equals(eligibility.ownerUserId())) {
            return new ReviewException(ErrorCode.NOT_ELIGIBILITY_OWNER,
                    "The entitlement belongs to another account");
        }
        if (eligibility.status() == EligibilityStatus.CLAIMED) {
            return new ReviewException(ErrorCode.ALREADY_REVIEWED,
                    "This entitlement already has a review");
        }
        if (eligibility.status() == EligibilityStatus.EXPIRED || !eligibility.expiresAt().isAfter(now)) {
            return new ReviewException(ErrorCode.ELIGIBILITY_EXPIRED, "The review window has closed");
        }
        // The conditional update matched nothing while every readable condition holds:
        // a concurrent claim committed between the two statements.
        return new ReviewException(ErrorCode.ALREADY_REVIEWED, "This entitlement already has a review");
    }

    private void validateContent(String content) {
        if (content == null) {
            return;
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty() || trimmed.length() > properties.maxContentLength()) {
            throw ReviewException.invalid("content",
                    "content must be non-blank and at most " + properties.maxContentLength() + " characters");
        }
    }

    /** Blank text is not content; it is stored as absent content. */
    private static String normalize(String content) {
        return content == null || content.isBlank() ? null : content;
    }
}
