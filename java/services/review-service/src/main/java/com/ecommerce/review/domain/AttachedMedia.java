package com.ecommerce.review.domain;

import java.util.UUID;

/**
 * One {@code review_media} link joined to the asset it points at.
 *
 * <p>No URL is carried: download URLs are signed at response time and never persisted,
 * because they expire ({@code docs/review/data.md 4}).
 */
public record AttachedMedia(
        UUID reviewId,
        UUID assetId,
        MediaType mediaType,
        String objectKey,
        int sortOrder) {
}
