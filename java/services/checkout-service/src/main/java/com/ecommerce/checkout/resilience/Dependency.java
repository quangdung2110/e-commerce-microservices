package com.ecommerce.checkout.resilience;

/**
 * The outbound calls of Checkout. Each has its own timeout, bulkhead and circuit breaker
 * (ADR-014); Order's POST and GET are separate because their budgets and failure meaning differ.
 */
public enum Dependency {
    CART("cart"),
    USER("user"),
    PRODUCT("product"),
    INVENTORY("inventory"),
    PROMOTION_CALCULATION("promotion_calculation"),
    PROMOTION_OPTIONS("promotion_options"),
    ORDER_POST("order_post"),
    ORDER_GET("order_get");

    private final String tag;

    Dependency(String tag) {
        this.tag = tag;
    }

    /** Metric label value and circuit breaker name. */
    public String tag() {
        return tag;
    }
}
