package com.ecommerce.review.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code USER_DELETED} data body Review consumes from Auth.
 *
 * <p>Auth publishes this directly after commit with at most three retries and no
 * outbox, so the event can be lost. Review has no synchronous Auth lookup and cannot
 * detect that absence: recovering a lost deletion is Auth's reconciliation or redrive,
 * and until it arrives there is no guard ({@code docs/review/interfaces.md 5}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserDeletedPayload(
        UUID userId,
        Instant deletedAt,
        String reason,
        Instant hardDeleteAfter) {
}
