package com.ecommerce.order.common;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Request ID and W3C trace context of the inbound request being served (contracts ADR-002).
 *
 * <p>Order has no tracing library, so it passes the inbound context through: outbound calls made
 * on the request thread carry the same {@code X-Request-Id}, {@code traceparent} and
 * {@code tracestate}. Order never invents a {@code traceparent}. Work that has no inbound request
 * (Temporal activities, relays) runs on threads where {@link #current()} is {@code null}.
 *
 * @param requestId   normalized request ID, always present
 * @param traceparent valid inbound {@code traceparent}, or {@code null}
 * @param tracestate  inbound {@code tracestate}, only when {@code traceparent} is present
 */
public record RequestCorrelation(String requestId, String traceparent, String tracestate) {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String TRACESTATE_HEADER = "tracestate";
    public static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{8,128}$";

    private static final Pattern REQUEST_ID = Pattern.compile(REQUEST_ID_PATTERN);
    private static final Pattern TRACEPARENT =
            Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");
    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_SPAN_ID = "0".repeat(16);
    /** W3C limit for a whole {@code tracestate} value. */
    private static final int MAX_TRACESTATE = 512;

    private static final ThreadLocal<RequestCorrelation> CURRENT = new ThreadLocal<>();

    /** The request being served on this thread, or {@code null} off the request path. */
    public static RequestCorrelation current() {
        return CURRENT.get();
    }

    public static void set(RequestCorrelation correlation) {
        CURRENT.set(correlation);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Normalizes inbound headers. A missing or malformed {@code X-Request-Id} never fails the request:
     * it is replaced by the trace ID of a valid {@code traceparent}, and otherwise by a new UUID.
     */
    public static RequestCorrelation fromHeaders(String requestId, String traceparent, String tracestate) {
        String parent = validTraceparent(traceparent);
        String state = parent != null && tracestate != null && !tracestate.isBlank()
                && tracestate.length() <= MAX_TRACESTATE ? tracestate : null;
        String id;
        if (requestId != null && REQUEST_ID.matcher(requestId).matches()) {
            id = requestId;
        } else if (parent != null) {
            id = traceId(parent);
        } else {
            id = UUID.randomUUID().toString();
        }
        return new RequestCorrelation(id, parent, state);
    }

    /** A fresh request ID for a call that has no inbound request. */
    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    /** Trace ID (32 hex) of this context, or {@code null} without a {@code traceparent}. */
    public String traceId() {
        return traceparent == null ? null : traceId(traceparent);
    }

    private static String validTraceparent(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        Matcher matcher = TRACEPARENT.matcher(trimmed);
        if (!matcher.matches() || "ff".equals(matcher.group(1))
                || ZERO_TRACE_ID.equals(matcher.group(2)) || ZERO_SPAN_ID.equals(matcher.group(3))) {
            return null;
        }
        return trimmed;
    }

    private static String traceId(String traceparent) {
        return traceparent.substring(3, 35);
    }
}
