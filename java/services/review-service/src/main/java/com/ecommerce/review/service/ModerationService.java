package com.ecommerce.review.service;

import com.ecommerce.review.common.Cursors;
import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.config.ReviewProperties;
import com.ecommerce.review.domain.AttachedMedia;
import com.ecommerce.review.domain.Eligibility;
import com.ecommerce.review.domain.ModerationQueueStatus;
import com.ecommerce.review.domain.Report;
import com.ecommerce.review.domain.Review;
import com.ecommerce.review.domain.ReviewStatus;
import com.ecommerce.review.dto.AdminDtos;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.outbox.ReviewEventData;
import com.ecommerce.review.outbox.ReviewEventFactory;
import com.ecommerce.review.outbox.ReviewEventType;
import com.ecommerce.review.repository.EligibilityRepository;
import com.ecommerce.review.repository.OutboxRepository;
import com.ecommerce.review.repository.ReportRepository;
import com.ecommerce.review.repository.ReviewMediaRepository;
import com.ecommerce.review.repository.ReviewRepository;
import com.ecommerce.review.repository.TransactionClock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WF-REVIEW-007: reporting, the automatic hide and the administrator's dismissal.
 *
 * <p>Counting and hiding happen in one transaction, so ten simultaneous reports around
 * the threshold produce exactly one hide and exactly one {@code REVIEW_HIDDEN}
 * (ADR-009).
 */
@Service
public class ModerationService {

    /** Reports shown per case; {@code active_report_count} carries the real number. */
    private static final int REPORTS_PER_CASE = 50;

    private final ReviewRepository reviews;
    private final EligibilityRepository eligibilities;
    private final ReportRepository reports;
    private final ReviewMediaRepository reviewMedia;
    private final PrivacyGuardService privacyGuard;
    private final OutboxRepository outbox;
    private final ReviewEventFactory events;
    private final ReviewAssembler assembler;
    private final TransactionClock clock;
    private final ReviewProperties properties;

    public ModerationService(ReviewRepository reviews,
                             EligibilityRepository eligibilities,
                             ReportRepository reports,
                             ReviewMediaRepository reviewMedia,
                             PrivacyGuardService privacyGuard,
                             OutboxRepository outbox,
                             ReviewEventFactory events,
                             ReviewAssembler assembler,
                             TransactionClock clock,
                             ReviewProperties properties) {
        this.reviews = reviews;
        this.eligibilities = eligibilities;
        this.reports = reports;
        this.reviewMedia = reviewMedia;
        this.privacyGuard = privacyGuard;
        this.outbox = outbox;
        this.events = events;
        this.assembler = assembler;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Records one report and hides the review when the threshold is reached.
     *
     * <p>A review that is not {@code APPROVED} answers {@code REVIEW_NOT_FOUND}: it is
     * not on the public surface, and a distinct "not reportable" answer would tell the
     * caller it had been hidden, which is exactly what the neutral 202 avoids saying
     * ({@code docs/review/domain.md 7}).
     */
    @Transactional
    public void report(UUID callerId, UUID reviewId, CommandDtos.CreateReviewReportRequest request) {
        privacyGuard.lockAndRequireUnguarded(callerId, "review-report");
        Instant now = clock.now();

        Review review = reviews.findByIdForUpdate(reviewId)
                .filter(candidate -> candidate.status() == ReviewStatus.APPROVED)
                .orElseThrow(() -> new ReviewException(ErrorCode.REVIEW_NOT_FOUND, "Review does not exist"));

        Eligibility eligibility = eligibilities.findByReviewId(reviewId)
                .orElseThrow(() -> new ReviewException(ErrorCode.REVIEW_NOT_FOUND, "Review does not exist"));
        if (callerId.equals(eligibility.ownerUserId())) {
            throw new ReviewException(ErrorCode.REVIEW_REPORT_FORBIDDEN,
                    "The author of a review cannot report it");
        }

        try {
            reports.insert(reviewId, callerId, request.reason(), request.detail());
        } catch (DuplicateKeyException e) {
            // The partial unique index excludes DISMISSED, so this only fires while the
            // caller still has an undismissed report on this review.
            throw new ReviewException(ErrorCode.ALREADY_REPORTED,
                    "An active report already exists for this review", e);
        }

        Review counted = reviews.recordReport(reviewId, now);
        if (counted.activeReportCount() >= properties.reportThreshold()) {
            Review hidden = reviews.hideByReport(reviewId, now);
            reports.markPendingActioned(reviewId, now);
            outbox.append(events.lifecycle(ReviewEventType.REVIEW_HIDDEN,
                    new ReviewEventData.Lifecycle(hidden.id(), hidden.productId(), hidden.skuId(),
                            hidden.rating(), hidden.hasMedia()),
                    now));
        }
    }

    /**
     * Dismisses every undismissed report of a review, restoring it when the threshold
     * had hidden it.
     *
     * <p>Idempotent by outcome: a review with nothing left to dismiss still answers with
     * its current state. A {@code REMOVED} review is never resurrected — a legal takedown
     * is not a moderation decision to overturn.
     */
    @Transactional
    public AdminDtos.AdminModerationResult dismissReports(UUID adminId, UUID reviewId) {
        Instant now = clock.now();
        Review review = reviews.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new ReviewException(ErrorCode.REVIEW_NOT_FOUND, "Review does not exist"));

        int dismissed = reports.dismissAll(reviewId, adminId, now);
        int active = reports.countActive(reviewId);
        boolean restore = review.status() == ReviewStatus.HIDDEN_BY_REPORT;

        Review updated = reviews.applyDismissal(reviewId, active, restore);
        if (restore) {
            // The single source of REVIEW_APPROVED: restoring a report-hidden review.
            // It is never emitted on create (ADR-008).
            outbox.append(events.lifecycle(ReviewEventType.REVIEW_APPROVED,
                    new ReviewEventData.Lifecycle(updated.id(), updated.productId(), updated.skuId(),
                            updated.rating(), updated.hasMedia()),
                    now));
        }
        return new AdminDtos.AdminModerationResult(updated.id(), updated.status(), dismissed,
                updated.activeReportCount());
    }

    /**
     * The one moderation queue, in either of its two states.
     *
     * <p>Both share the ascending {@code (first_reported_at, id)} keyset, so a page keeps
     * one meaning across the hide boundary and the longest-open case is handled first.
     * Each entry carries the review, its media and its undismissed reports, so one read
     * is enough to decide.
     */
    @Transactional(readOnly = true)
    public AdminDtos.AdminModerationCasePage listQueue(ModerationQueueStatus status, String cursor, int limit) {
        Cursors.Position position = Cursors.decode(cursor);
        List<Review> rows = reviews.listModerationQueue(status,
                position == null ? null : position.timestamp(),
                position == null ? null : position.id(),
                limit);

        List<UUID> ids = rows.stream().map(Review::id).toList();
        Map<UUID, List<AttachedMedia>> media = reviewMedia.loadByReviewIds(ids);
        Map<UUID, List<Report>> byReview = reports.loadUndismissedByReviewIds(ids, REPORTS_PER_CASE);

        List<AdminDtos.AdminModerationCase> items = rows.stream()
                .map(review -> assembler.toModerationCase(review,
                        ReviewAssembler.mediaOf(media, review.id()),
                        byReview.getOrDefault(review.id(), List.of())))
                .toList();

        String next = rows.size() < limit ? null
                : Cursors.encode(rows.get(rows.size() - 1).firstReportedAt(), rows.get(rows.size() - 1).id());
        return new AdminDtos.AdminModerationCasePage(items, next);
    }
}
