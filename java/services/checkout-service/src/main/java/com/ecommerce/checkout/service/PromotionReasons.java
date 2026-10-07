package com.ecommerce.checkout.service;

import java.util.Set;

/**
 * Promotion eligibility reasons relayed for display (OpenAPI {@code PromotionReason}). A value this
 * Checkout build does not know becomes {@code UNKNOWN} so a new Promotion reason never breaks a response.
 */
final class PromotionReasons {

    private static final Set<String> KNOWN = Set.of("UNKNOWN", "ELIGIBLE", "CONDITIONS_NOT_MET", "MIN_ORDER_NOT_MET",
            "VOUCHER_NOT_OWNED", "VOUCHER_EXPIRED", "ALREADY_USED_VOUCHER", "CAMPAIGN_NOT_ACTIVE",
            "PROMOTION_DISABLED", "BUDGET_EXHAUSTED");

    private PromotionReasons() {
    }

    static String map(String reason) {
        return reason != null && KNOWN.contains(reason) ? reason : "UNKNOWN";
    }
}
