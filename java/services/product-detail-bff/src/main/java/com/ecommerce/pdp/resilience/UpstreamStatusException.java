package com.ecommerce.pdp.resilience;

/**
 * Dependency trả status không mong đợi. Chỉ {@code 400}/{@code 401}/{@code 403} là lỗi cấu
 * hình/authorization mà circuit breaker bỏ qua; {@code 5xx} tính vào circuit breaker; mọi
 * status khác (4xx khác, 3xx) nằm ngoài contract của provider nên cũng tính là lỗi
 * (ADR-004 §3: "response không đúng contract").
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

    /** {@code 400}/{@code 401}/{@code 403}: request, service identity hoặc token sai. */
    public boolean isConfigurationError() {
        return status == 400 || status == 401 || status == 403;
    }
}
