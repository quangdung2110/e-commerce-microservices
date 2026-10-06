package com.ecommerce.review.service;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.domain.Eligibility;
import com.ecommerce.review.domain.MediaDeletionReason;
import com.ecommerce.review.domain.Review;
import com.ecommerce.review.domain.ReviewStatus;
import com.ecommerce.review.domain.Takedown;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.metrics.ReviewMetrics;
import com.ecommerce.review.outbox.ReviewEventData;
import com.ecommerce.review.outbox.ReviewEventFactory;
import com.ecommerce.review.outbox.ReviewEventType;
import com.ecommerce.review.repository.EligibilityRepository;
import com.ecommerce.review.repository.MediaAssetRepository;
import com.ecommerce.review.repository.OutboxRepository;
import com.ecommerce.review.repository.ReviewMediaRepository;
import com.ecommerce.review.repository.ReviewRepository;
import com.ecommerce.review.repository.TakedownRepository;
import com.ecommerce.review.repository.TransactionClock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WF-REVIEW-012: the irreversible legal takedown.
 *
 * <p>One administrator with the right role performs it, and the audit record names that
 * person — read from trusted Gateway identity, because an approver identifier taken from
 * the request body would prove nothing (ADR-010). Neither the legal case, the internal
 * reason nor the acting administrator appears in the response or in either event.
 */
@Service
public class TakedownService {

    private final ReviewRepository reviews;
    private final EligibilityRepository eligibilities;
    private final TakedownRepository takedowns;
    private final ReviewMediaRepository reviewMedia;
    private final MediaAssetRepository mediaAssets;
    private final PrivacyGuardService privacyGuard;
    private final OutboxRepository outbox;
    private final ReviewEventFactory events;
    private final TransactionClock clock;
    private final ReviewMetrics metrics;

    public TakedownService(ReviewRepository reviews,
                           EligibilityRepository eligibilities,
                           TakedownRepository takedowns,
                           ReviewMediaRepository reviewMedia,
                           MediaAssetRepository mediaAssets,
                           PrivacyGuardService privacyGuard,
                           OutboxRepository outbox,
                           ReviewEventFactory events,
                           TransactionClock clock,
                           ReviewMetrics metrics) {
        this.reviews = reviews;
        this.eligibilities = eligibilities;
        this.takedowns = takedowns;
        this.reviewMedia = reviewMedia;
        this.mediaAssets = mediaAssets;
        this.privacyGuard = privacyGuard;
        this.outbox = outbox;
        this.events = events;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional
    public CommandDtos.TakedownReceipt takeDown(UUID adminId, UUID reviewId,
                                                CommandDtos.CreateTakedownRequest request) {
        String legalCaseId = request.legalCaseId().trim();
        String reason = request.reason().trim();
        if (legalCaseId.isEmpty()) {
            throw ReviewException.invalid("legal_case_id", "legal_case_id must be non-blank");
        }
        if (reason.isEmpty()) {
            throw ReviewException.invalid("reason", "reason must be non-blank");
        }

        Eligibility owner = eligibilities.findByReviewId(reviewId)
                .orElseThrow(() -> new ReviewException(ErrorCode.REVIEW_NOT_FOUND, "Review does not exist"));
        boolean guarded = owner.ownerUserId() != null
                && privacyGuard.lockAndCheck(owner.ownerUserId(), "admin-takedown-notification");

        Review review = reviews.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new ReviewException(ErrorCode.REVIEW_NOT_FOUND, "Review does not exist"));
        if (review.status() == ReviewStatus.REMOVED || takedowns.findByReviewId(reviewId).isPresent()) {
            throw new ReviewException(ErrorCode.TAKEDOWN_ALREADY_EXISTS,
                    "This review already has a legal takedown");
        }

        Eligibility locked = eligibilities.findByReviewIdForUpdate(reviewId)
                .orElseThrow(() -> new ReviewException(ErrorCode.REVIEW_NOT_FOUND, "Review does not exist"));

        Instant now = clock.now();
        Takedown takedown;
        try {
            takedown = takedowns.insert(reviewId, legalCaseId, adminId, reason);
        } catch (DuplicateKeyException e) {
            throw new ReviewException(ErrorCode.TAKEDOWN_ALREADY_EXISTS,
                    "This review already has a legal takedown", e);
        }

        // was_counted is decided before the status changes: it is what tells the rating
        // aggregator whether there is anything to subtract.
        boolean wasCounted = review.status() == ReviewStatus.APPROVED;
        Review removed = reviews.markRemoved(reviewId);

        List<UUID> detached = reviewMedia.detachAll(reviewId);
        mediaAssets.queueDeletion(detached, MediaDeletionReason.TAKEDOWN, now);

        outbox.append(events.removed(new ReviewEventData.Removed(
                removed.id(), removed.productId(), removed.skuId(),
                removed.rating(), review.hasMedia(), wasCounted), now));

        if (!guarded && locked.ownerUserId() != null) {
            outbox.append(events.notification(ReviewEventType.REVIEW_REMOVAL_NOTIFICATION_REQUESTED,
                    new ReviewEventData.Notification(removed.id(), removed.productId(), locked.ownerUserId()),
                    now));
            metrics.notificationRequest("removal", "written");
        } else {
            metrics.notificationRequest("removal", "suppressed_by_guard");
        }

        return new CommandDtos.TakedownReceipt(takedown.id(), reviewId, takedown.legalCaseId(),
                takedown.createdAt());
    }
}
