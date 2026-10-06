package com.ecommerce.review.domain;

/**
 * {@code review.review_media_asset_state}.
 *
 * <p>There is no {@code EXPIRED} state: an unattached asset that reaches its deadline
 * goes straight into {@link #DELETE_PENDING} with {@code UNATTACHED_EXPIRED} and only
 * becomes {@link #DELETED} once storage confirms (ADR-011).
 */
public enum MediaAssetState {
    ISSUED,
    VERIFIED,
    ATTACHED,
    DELETE_PENDING,
    DELETED
}
