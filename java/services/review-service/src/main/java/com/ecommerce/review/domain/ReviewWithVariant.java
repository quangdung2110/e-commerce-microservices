package com.ecommerce.review.domain;

/**
 * A review joined to the variant name its entitlement snapshotted at order completion.
 *
 * <p>The snapshot lives on the entitlement, not on the review, and Review never calls
 * Order or Product to refresh it (ADR-007). It is null for historic orders whose
 * producer had no snapshot to send.
 */
public record ReviewWithVariant(Review review, String variantName) {
}
