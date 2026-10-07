package com.ecommerce.review.domain;

/**
 * The two states of the single admin moderation queue.
 *
 * <p>{@link #REPORTED} is an {@code APPROVED} review with undismissed reports, still
 * settleable before the threshold fires. {@link #HIDDEN_BY_REPORT} is one the threshold
 * already hid — and the only route to the {@code review_id} a dismissal needs, because
 * hiding moves every {@code PENDING} report to {@code ACTIONED} (WF-REVIEW-007).
 */
public enum ModerationQueueStatus {
    REPORTED,
    HIDDEN_BY_REPORT
}
