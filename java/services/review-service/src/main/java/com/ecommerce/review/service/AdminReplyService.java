package com.ecommerce.review.service;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.domain.Eligibility;
import com.ecommerce.review.domain.Review;
import com.ecommerce.review.domain.ReviewStatus;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.dto.PublicDtos;
import com.ecommerce.review.metrics.ReviewMetrics;
import com.ecommerce.review.outbox.ReviewEventData;
import com.ecommerce.review.outbox.ReviewEventFactory;
import com.ecommerce.review.outbox.ReviewEventType;
import com.ecommerce.review.repository.AdminReplyRepository;
import com.ecommerce.review.repository.EligibilityRepository;
import com.ecommerce.review.repository.OutboxRepository;
import com.ecommerce.review.repository.ReviewRepository;
import com.ecommerce.review.repository.TransactionClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * WF-REVIEW-006: the single official reply.
 *
 * <p>{@code PUT} is a resource-level upsert. Only the first creation may request a
 * notification, and only when the eligibility owner carries no deletion guard. A guard
 * never rejects the reply itself — the merchant still answers the review — it only
 * suppresses the outbox row that would carry that person's identifier
 * (REVIEW-INV-012).
 */
@Service
public class AdminReplyService {

    private final ReviewRepository reviews;
    private final EligibilityRepository eligibilities;
    private final AdminReplyRepository adminReplies;
    private final PrivacyGuardService privacyGuard;
    private final OutboxRepository outbox;
    private final ReviewEventFactory events;
    private final ReviewAssembler assembler;
    private final TransactionClock clock;
    private final ReviewMetrics metrics;

    public AdminReplyService(ReviewRepository reviews,
                             EligibilityRepository eligibilities,
                             AdminReplyRepository adminReplies,
                             PrivacyGuardService privacyGuard,
                             OutboxRepository outbox,
                             ReviewEventFactory events,
                             ReviewAssembler assembler,
                             TransactionClock clock,
                             ReviewMetrics metrics) {
        this.reviews = reviews;
        this.eligibilities = eligibilities;
        this.adminReplies = adminReplies;
        this.privacyGuard = privacyGuard;
        this.outbox = outbox;
        this.events = events;
        this.assembler = assembler;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional
    public PublicDtos.OfficialReply putReply(UUID adminId, UUID reviewId, CommandDtos.PutAdminReplyRequest request) {
        // The owner is read first so the subject lock can be taken before the review row
        // is locked, in the same order the privacy consumer takes them (WF-REVIEW-006).
        Eligibility owner = eligibilities.findByReviewId(reviewId)
                .orElseThrow(() -> new ReviewException(ErrorCode.REVIEW_NOT_FOUND, "Review does not exist"));
        boolean guarded = owner.ownerUserId() != null
                && privacyGuard.lockAndCheck(owner.ownerUserId(), "admin-reply-notification");

        Review review = reviews.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new ReviewException(ErrorCode.REVIEW_NOT_FOUND, "Review does not exist"));
        if (review.status() != ReviewStatus.APPROVED) {
            throw new ReviewException(ErrorCode.REVIEW_NOT_REPLYABLE, "Only an approved review can be replied to");
        }

        // Re-read under the lock: the privacy worker may have purged the owner between
        // the first read and the lock, and a purged owner is always guarded.
        Eligibility locked = eligibilities.findByReviewIdForUpdate(reviewId)
                .orElseThrow(() -> new ReviewException(ErrorCode.REVIEW_NOT_FOUND, "Review does not exist"));
        boolean recipientAvailable = locked.ownerUserId() != null && !guarded;

        String content = request.content().trim();
        if (content.isEmpty()) {
            throw ReviewException.invalid("content", "content must be non-blank");
        }
        AdminReplyRepository.UpsertResult result = adminReplies.upsert(reviewId, adminId, content);

        if (result.created() && recipientAvailable) {
            Instant now = clock.now();
            outbox.append(events.notification(ReviewEventType.REVIEW_REPLY_NOTIFICATION_REQUESTED,
                    new ReviewEventData.Notification(review.id(), review.productId(), locked.ownerUserId()),
                    now));
            metrics.notificationRequest("reply", "written");
        } else if (result.created()) {
            // A retained raw owner identifier inside the hard-delete window is not
            // permission to notify: the guard decides, not the column (ADR-007).
            metrics.notificationRequest("reply", "suppressed_by_guard");
        }

        return assembler.toOfficialReply(result.reply());
    }
}
