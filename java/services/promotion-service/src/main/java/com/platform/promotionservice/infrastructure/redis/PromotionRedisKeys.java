package com.platform.promotionservice.infrastructure.redis;

/**
 * Mười một key pattern của Redis runtime (docs/promotion/data.md §6, ADR-015).
 * Key theo promotion dùng hash tag {@code {promotion_id}} để multi-key Lua cùng
 * cluster slot. Không có key nào là source of truth.
 */
public final class PromotionRedisKeys {

    public static final String RULES_VERSION = "promo:rules:version";
    public static final String CAMPAIGN_SCHEDULE = "promo:campaign:schedule";

    private PromotionRedisKeys() {
    }

    private static String tag(long promotionId) {
        return "promo:{" + promotionId + "}";
    }

    public static String snapshot(long promotionId) {
        return tag(promotionId) + ":snapshot";
    }

    public static String runtime(long promotionId) {
        return tag(promotionId) + ":runtime";
    }

    public static String claimPool(long promotionId) {
        return tag(promotionId) + ":claim_pool";
    }

    public static String claimedUsers(long promotionId) {
        return tag(promotionId) + ":claimed_users";
    }

    public static String pendingClaimsData(long promotionId) {
        return tag(promotionId) + ":pending_claims:data";
    }

    public static String pendingClaimsIndex(long promotionId) {
        return tag(promotionId) + ":pending_claims:index";
    }

    public static String reservation(long promotionId, long orderId) {
        return tag(promotionId) + ":reservation:" + orderId;
    }

    public static String reservationIndex(long promotionId) {
        return tag(promotionId) + ":reservation:index";
    }

    public static String reconcileLock(long promotionId) {
        return tag(promotionId) + ":reconcile_lock";
    }

    /** Member của campaign schedule ZSET: {campaign_id}:{revision}:{START|END}. */
    public static String scheduleMember(long campaignId, long revision, String trigger) {
        return campaignId + ":" + revision + ":" + trigger;
    }
}
