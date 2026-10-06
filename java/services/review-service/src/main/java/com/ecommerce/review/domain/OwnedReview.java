package com.ecommerce.review.domain;

/**
 * A review joined to what its entitlement snapshotted at order completion, for the owner
 * surface.
 *
 * <p>The snapshots live on the entitlement, not on the review, and Review never calls
 * Order or Product to refresh them (ADR-007). Every one is null for entitlements granted
 * before the product snapshot existed.
 *
 * @param variantName     the variant name the buyer saw at purchase time
 * @param productName     the product name the buyer saw at purchase time
 * @param productImageUrl the product thumbnail the buyer saw at purchase time
 */
public record OwnedReview(Review review, String variantName, String productName, String productImageUrl) {
}
