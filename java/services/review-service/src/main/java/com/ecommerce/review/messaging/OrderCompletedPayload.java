package com.ecommerce.review.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The {@code ORDER_COMPLETED} data body Review consumes.
 *
 * <p>{@code orderId}, {@code productId} and {@code skuId} are opaque strings owned by
 * other services. Review compares them for equality and never parses, sorts or derives
 * anything from them, so Order changing its internal format cannot break Review. They
 * are strings rather than numbers because a JSON number is an IEEE-754 double in
 * JavaScript, which silently rounds anything above 2^53-1.
 *
 * @param completedAt the business timestamp the review window is measured from, never
 *                    the moment Review ingested the event (REVIEW-BR-002)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCompletedPayload(
        String orderId,
        UUID userId,
        Instant completedAt,
        List<Item> items) {

    /**
     * {@code variantName}, {@code productName} and {@code productImageUrl} are nullable:
     * historic orders carry none of them and current producers send them when the order
     * line snapshot has them.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String productId, String skuId, String variantName,
                       String productName, String productImageUrl, Integer quantity) {
    }
}
