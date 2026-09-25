package com.ecommerce.pdp.resilience;

/** Ba dependency của PDP; mỗi cái có timeout, bulkhead và circuit breaker riêng (ADR-004). */
public enum Dependency {
    PRODUCT("product"),
    INVENTORY("inventory"),
    REVIEW("review");

    private final String tag;

    Dependency(String tag) {
        this.tag = tag;
    }

    /** Giá trị label metric và tên circuit breaker. */
    public String tag() {
        return tag;
    }
}
