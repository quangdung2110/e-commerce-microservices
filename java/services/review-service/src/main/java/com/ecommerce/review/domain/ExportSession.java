package com.ecommerce.review.domain;

import java.time.Instant;
import java.util.UUID;

/** A row of {@code rating_export_sessions}: one Search rating export run (ADR-006). */
public record ExportSession(
        UUID id,
        String requestedBy,
        ExportSessionStatus status,
        Instant createdAt,
        Instant expiresAt) {

    public boolean expired(Instant now) {
        return status == ExportSessionStatus.EXPIRED || !expiresAt.isAfter(now);
    }
}
