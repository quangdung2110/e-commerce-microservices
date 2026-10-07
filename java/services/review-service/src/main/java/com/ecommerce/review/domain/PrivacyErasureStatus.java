package com.ecommerce.review.domain;

/** {@code review.privacy_erasure_status}. Any of these is a committed deletion guard. */
public enum PrivacyErasureStatus {
    RECEIVED,
    ANONYMIZED,
    PURGED,
    FAILED
}
