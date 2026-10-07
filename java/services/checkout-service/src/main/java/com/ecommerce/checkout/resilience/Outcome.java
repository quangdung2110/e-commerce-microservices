package com.ecommerce.checkout.resilience;

/**
 * Classified outcome of one dependency call. It is the single source for the metric label, for
 * whether the circuit breaker counts the call, and for the public mapping
 * (operations.md, "Health va degradation").
 */
public enum Outcome {
    SUCCESS("success"),
    /** The dependency answered a valid business result (address 404, REJECT_ALL, eligible=false, Order 409). */
    BUSINESS_REJECT("business_reject"),
    TIMEOUT("timeout"),
    CONNECT_ERROR("connect_error"),
    SERVER_ERROR("5xx"),
    /** 400/401/403/429: configuration or quota problem; alerting, not a provider health signal. */
    CLIENT_ERROR("4xx"),
    CONTRACT_VIOLATION("contract_violation"),
    CIRCUIT_OPEN("circuit_open"),
    BULKHEAD_REJECTED("bulkhead_rejected"),
    /** The remaining deadline was too short for the wave; nothing was sent. */
    BUDGET_SKIPPED("budget_skipped");

    private final String tag;

    Outcome(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    /** {@code true} when a request was actually sent to the dependency. */
    public boolean called() {
        return this != CIRCUIT_OPEN && this != BULKHEAD_REJECTED && this != BUDGET_SKIPPED;
    }
}
