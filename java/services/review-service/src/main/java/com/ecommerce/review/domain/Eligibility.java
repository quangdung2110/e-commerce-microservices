package com.ecommerce.review.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code review_eligibilities}: the right to write exactly one review for one
 * purchased SKU of one order.
 *
 * @param orderId             opaque identifier owned by Order; compared for equality only
 * @param completedAt         the business timestamp from {@code ORDER_COMPLETED}
 * @param expiresAt           {@code completedAt + review_window_days}, never derived from
 *                            ingestion time (REVIEW-BR-002)
 * @param ownerUserId         null once the privacy hard purge ran
 * @param variantNameSnapshot nullable snapshot for historic orders
 * @param productNameSnapshot nullable product name the buyer saw at purchase time
 * @param productImageUrlSnapshot nullable thumbnail URL of that product
 */
public record Eligibility(
        UUID id,
        String orderId,
        String productId,
        String skuId,
        String variantNameSnapshot,
        String productNameSnapshot,
        String productImageUrlSnapshot,
        UUID ownerUserId,
        Instant ownerErasedAt,
        EligibilityStatus status,
        Instant completedAt,
        Instant expiresAt,
        Instant claimedAt,
        Instant createdAt,
        Instant updatedAt) {
}
