package com.ecommerce.review.domain;

/**
 * {@code review.report_status}.
 *
 * <p>{@link #ACTIONED} is the automatic decision taken when the threshold hid the
 * review: it has a {@code resolved_at} but no admin. Only {@link #DISMISSED} is an
 * administrator's judgement.
 */
public enum ReportStatus {
    PENDING,
    ACTIONED,
    DISMISSED
}
