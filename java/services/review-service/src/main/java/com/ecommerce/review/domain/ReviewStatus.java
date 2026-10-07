package com.ecommerce.review.domain;

/** {@code review.review_status}. Only {@link #APPROVED} is public. */
public enum ReviewStatus {
    APPROVED,
    HIDDEN_BY_REPORT,
    /** Terminal: a legal takedown is never reversed by a dismissal (ADR-008). */
    REMOVED
}
