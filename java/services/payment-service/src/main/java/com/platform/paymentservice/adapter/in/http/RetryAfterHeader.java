package com.platform.paymentservice.adapter.in.http;

import org.springframework.http.HttpHeaders;

/**
 * {@code Retry-After} for problem responses (contracts ADR-005): every retryable 503 and every 429 carries it,
 * a 503 with {@code retryable=false} (for example {@code PROVIDER_REQUEST_REJECTED}) never does.
 */
final class RetryAfterHeader {

    /** Wait advertised when Payment has no better estimate of when its dependency recovers. */
    static final int UNAVAILABLE_SECONDS = 2;
    static final int THROTTLED_SECONDS = 1;

    private RetryAfterHeader() {
    }

    static void apply(HttpHeaders headers, int status, boolean retryable) {
        if (status == 503 && retryable) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(UNAVAILABLE_SECONDS));
        } else if (status == 429) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(THROTTLED_SECONDS));
        }
    }
}
