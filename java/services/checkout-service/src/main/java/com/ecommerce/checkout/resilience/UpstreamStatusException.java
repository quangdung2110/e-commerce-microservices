package com.ecommerce.checkout.resilience;

/**
 * A provider answered a status that is not a usable result. gRPC statuses are mapped onto the
 * HTTP status that classifies them the same way (INVALID_ARGUMENT 400, UNAUTHENTICATED 401,
 * PERMISSION_DENIED 403, everything else 503).
 */
public class UpstreamStatusException extends RuntimeException {

    private final int status;
    private final String code;

    public UpstreamStatusException(int status) {
        this(status, null);
    }

    public UpstreamStatusException(int status, String code) {
        super("upstream status " + status + (code == null ? "" : " " + code));
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    /** Provider problem {@code code}, when the body carried one. */
    public String code() {
        return code;
    }

    public boolean isServerError() {
        return status >= 500;
    }

    /** 429 is a quota signal for the provider's own limiter; it neither opens the circuit nor alerts as config. */
    public boolean isRateLimited() {
        return status == 429;
    }
}
