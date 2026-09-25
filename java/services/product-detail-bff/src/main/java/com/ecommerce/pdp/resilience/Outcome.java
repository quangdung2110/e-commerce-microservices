package com.ecommerce.pdp.resilience;

/**
 * Kết quả của một lời gọi dependency. Là nguồn duy nhất cho metric, cho quyết định
 * có tính vào circuit breaker hay không, và cho việc map sang {@code 503}/{@code UNKNOWN}/
 * {@code UNAVAILABLE} (ADR-004 §4, operations.md §Health và degradation).
 */
public enum Outcome {
    SUCCESS("success", true),
    TIMEOUT("timeout", true),
    CONNECT_ERROR("connect_error", true),
    SERVER_ERROR("5xx", true),
    /** {@code 400}/{@code 401}/{@code 403}: lỗi cấu hình, alert riêng, không tính circuit. */
    CLIENT_ERROR("4xx", true),
    CONTRACT_VIOLATION("contract_violation", true),
    CIRCUIT_OPEN("circuit_open", false),
    BULKHEAD_REJECTED("bulkhead_rejected", false),
    /** Deadline còn dưới ngưỡng trước pha của dependency. */
    BUDGET_SKIPPED("budget_skipped", false),
    /** Dependency bị tắt bằng cờ; không tính SLO degrade, không alert (ADR-007). */
    DISABLED("disabled", false);

    private final String tag;
    private final boolean called;

    Outcome(String tag, boolean called) {
        this.tag = tag;
        this.called = called;
    }

    public String tag() {
        return tag;
    }

    /** {@code false} khi BFF không phát request nào tới dependency. */
    public boolean called() {
        return called;
    }
}
