package com.ecommerce.pdp.resilience;

/**
 * Dependency trả status không mong đợi. {@code 5xx} tính vào circuit breaker; {@code 4xx}
 * là lỗi cấu hình/authorization nên bị circuit breaker bỏ qua (ADR-004 §3).
 */
public class UpstreamStatusException extends RuntimeException {

    private final int status;

    public UpstreamStatusException(int status) {
        super("Upstream answered HTTP " + status);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public boolean isServerError() {
        return status >= 500;
    }
}
