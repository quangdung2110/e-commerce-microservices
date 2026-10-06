package com.ecommerce.review.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code review_media_assets}.
 *
 * <p>{@code objectKey} is opaque, carries no user identifier and never leaves the
 * service: the public contract exchanges {@code asset_id} and short-lived signed URLs
 * only (ADR-011). In v1 {@code expiresAt} is both the signed-upload expiry and the
 * deadline for attaching an otherwise-unattached asset.
 */
public record MediaAsset(
        UUID id,
        UUID ownerUserId,
        Instant ownerErasedAt,
        String objectKey,
        MediaType mediaType,
        MediaAssetState uploadState,
        String contentType,
        Long byteSize,
        String checksum,
        Instant expiresAt,
        Instant verifiedAt,
        Instant attachedAt,
        MediaDeletionReason deletionReason,
        Instant deleteRequestedAt,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Whether this asset can still be attached. An asset already queued for cleanup is
     * not usable, and neither is one past its attachment deadline: the expiry sweep may
     * simply not have reached it yet (ADR-011).
     */
    public boolean attachable(Instant now) {
        return (uploadState == MediaAssetState.ISSUED || uploadState == MediaAssetState.VERIFIED)
                && expiresAt.isAfter(now);
    }
}
