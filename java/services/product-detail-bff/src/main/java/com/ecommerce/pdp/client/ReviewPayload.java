package com.ecommerce.pdp.client;

import com.ecommerce.pdp.resilience.ContractViolationException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code ProductReviewPdpSummary} của Review (contracts/http/review/internal/v1). Aggregate
 * 0 với preview rỗng là hợp lệ: Product chưa có review, không phải lỗi.
 */
public record ReviewPayload(String productId, Aggregate aggregate, List<PreviewItem> preview) {

    public record Aggregate(Double averageRating, Long totalReviews, Long totalWithMedia) {
    }

    public record PreviewItem(String reviewId,
                              Integer rating,
                              String content,
                              String authorName,
                              String variantName,
                              Boolean hasMedia,
                              OffsetDateTime createdAt) {
    }

    public ReviewPayload validated() {
        require(aggregate != null, "aggregate is required");
        require(aggregate.averageRating() != null
                        && aggregate.averageRating() >= 0 && aggregate.averageRating() <= 5,
                "aggregate.average_rating must be within 0..5");
        require(aggregate.totalReviews() != null && aggregate.totalReviews() >= 0,
                "aggregate.total_reviews must be >= 0");
        require(aggregate.totalWithMedia() != null && aggregate.totalWithMedia() >= 0,
                "aggregate.total_with_media must be >= 0");
        require(preview != null && preview.size() <= 5, "preview must be a list of at most 5");
        for (PreviewItem item : preview) {
            require(item != null, "preview item is null");
            require(isUuid(item.reviewId()), "preview.review_id must be a UUID");
            require(item.rating() != null && item.rating() >= 1 && item.rating() <= 5,
                    "preview.rating must be within 1..5");
            require(item.authorName() != null && !item.authorName().isEmpty() && item.authorName().length() <= 255,
                    "preview.author_name must be a non-empty string of at most 255");
            require(item.variantName() == null || item.variantName().length() <= 255,
                    "preview.variant_name must be at most 255");
            require(item.hasMedia() != null, "preview.has_media is required");
            require(item.createdAt() != null, "preview.created_at is required");
        }
        return this;
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ContractViolationException("Review: " + message);
        }
    }
}
