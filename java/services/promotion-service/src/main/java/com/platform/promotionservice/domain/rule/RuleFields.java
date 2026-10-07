package com.platform.promotionservice.domain.rule;

import java.util.Set;

/**
 * Allowlist field của rule_schema_version 1 (ADR-009). {@code user.id} lấy từ
 * trusted identity; {@code user.tier} từ verified claims hoặc trusted service
 * snapshot, không lấy từ facts do client gửi.
 */
public final class RuleFields {

    public static final int SCHEMA_VERSION_V1 = 1;

    public static final String CART_TOTAL_AMOUNT = "cart.total_amount";
    public static final String CART_PAYMENT_METHOD = "cart.payment_method";
    public static final String CART_CHECKOUT_CHANNEL = "cart.checkout_channel";
    public static final String CART_ITEMS_CATEGORY_ID = "cart.items.category_id";
    public static final String USER_ID = "user.id";
    public static final String USER_TIER = "user.tier";

    public static final Set<String> V1 = Set.of(
            CART_TOTAL_AMOUNT, CART_PAYMENT_METHOD, CART_CHECKOUT_CHANNEL,
            CART_ITEMS_CATEGORY_ID, USER_ID, USER_TIER);

    /** Field mà facts từ client được phép cung cấp khi trusted mapping chưa có giá trị. */
    public static final Set<String> CLIENT_FACT_OVERRIDABLE = Set.of(CART_CHECKOUT_CHANNEL);

    private RuleFields() {
    }

    public static boolean isAllowed(int schemaVersion, String field) {
        return schemaVersion == SCHEMA_VERSION_V1 && V1.contains(field);
    }
}
