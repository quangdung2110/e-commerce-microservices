package com.ecommerce.checkout.client;

import com.ecommerce.checkout.resilience.Deadline;

/**
 * What every dependency call needs from the inbound request.
 *
 * @param requestId   normalized request ID (interfaces.md, "Header gui cho dependency"): sent as
 *                    {@code X-Request-Id} to Cart, User, Product, Promotion and Order, and as
 *                    {@code X-Correlation-ID} to Inventory
 * @param traceId     32-hex trace ID, used for the problem {@code trace_id} and as the request-ID fallback
 * @param traceparent W3C {@code traceparent} of the server span, forwarded over gRPC (the HTTP
 *                    clients get theirs from Micrometer's WebClient instrumentation); may be {@code null}
 * @param causationId Preview's {@code Idempotency-Key}, sent to Inventory as {@code X-Causation-ID};
 *                    {@code null} when the route has none
 */
public record RequestContext(String requestId, String traceId, String traceparent, String causationId,
                             Deadline deadline) {

    public RequestContext withDeadline(Deadline replacement) {
        return new RequestContext(requestId, traceId, traceparent, causationId, replacement);
    }

    public RequestContext withCausationId(String replacement) {
        return new RequestContext(requestId, traceId, traceparent, replacement, deadline);
    }
}
