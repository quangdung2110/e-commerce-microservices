package com.ecommerce.review.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code privacy_erasure_requests}.
 *
 * <p>{@code subjectMatchKey} is the durable HMAC deletion guard. It outlives the raw
 * {@code subjectUserId}, which the hard purge nulls, so late or replayed input can be
 * recognised without retaining a direct identifier (REVIEW-INV-012). It is pseudonymous
 * data: never logged, never a metric label, never returned by an API.
 */
public record PrivacyRequest(
        UUID id,
        UUID sourceEventId,
        UUID subjectUserId,
        byte[] subjectMatchKey,
        short subjectKeyVersion,
        String deletionReason,
        Instant deletedAt,
        Instant hardDeleteAfter,
        PrivacyErasureStatus status,
        Instant anonymizedAt,
        Instant purgedAt,
        String failureDetail) {
}
