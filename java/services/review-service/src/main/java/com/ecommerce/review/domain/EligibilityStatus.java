package com.ecommerce.review.domain;

/**
 * {@code review.eligibility_status}. Three states only: the edit quota lives on
 * {@code reviews.edit_count}, not here (ADR-001).
 */
public enum EligibilityStatus {
    /** The buyer may still create the one review. */
    PENDING,
    /** A review was created in the same transaction that claimed this entitlement. */
    CLAIMED,
    /** The review window closed before a review was created. */
    EXPIRED
}
