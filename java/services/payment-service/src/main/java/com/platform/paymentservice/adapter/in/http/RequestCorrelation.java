package com.platform.paymentservice.adapter.in.http;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Request ID, W3C trace context and caller deadline of the inbound request being served
 * (contracts ADR-002 and ADR-003).
 *
 * <p>This service has no tracing library, so it passes the inbound context through and never
 * invents a {@code traceparent}. Work that has no inbound request (consumers, schedulers) runs on
 * threads where {@link #current()} is {@code null}.
 *
 * @param requestId       normalized request ID, always present
 * @param traceparent     valid inbound {@code traceparent}, or {@code null}
 * @param tracestate      inbound {@code tracestate}, only when {@code traceparent} is present
 * @param timeoutMs       inbound {@code X-Request-Timeout-Ms} (1..600000), or {@code null}
 * @param receivedAtNanos {@link System#nanoTime()} when the request was received
 */
public record RequestCorrelation(String requestId, String traceparent, String tracestate, Long timeoutMs,
                                 long receivedAtNanos) {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String TRACESTATE_HEADER = "tracestate";
    public static final String TIMEOUT_HEADER = "X-Request-Timeout-Ms";
    public static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{8,128}$";

    private static final Pattern REQUEST_ID = Pattern.compile(REQUEST_ID_PATTERN);
    private static final Pattern TRACEPARENT =
            Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");
    private static final Pattern TIMEOUT = Pattern.compile("^[0-9]{1,6}$");
    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_SPAN_ID = "0".repeat(16);
    /** W3C limit for a whole {@code tracestate} value. */
    private static final int MAX_TRACESTATE = 512;
    private static final long MAX_TIMEOUT_MS = 600_000;

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

    /** Same as the four-argument form, for a request that carries no deadline header. */
    public static RequestCorrelation fromHeaders(String requestId, String traceparent, String tracestate) {
        return fromHeaders(requestId, traceparent, tracestate, null);
    }

    /**
     * Normalizes inbound headers. A missing or malformed {@code X-Request-Id} never fails the request:
     * it is replaced by the trace ID of a valid {@code traceparent}, and otherwise by a new UUID. A
     * missing or malformed {@code X-Request-Timeout-Ms} means "no caller deadline".
     */
    public static RequestCorrelation fromHeaders(String requestId, String traceparent, String tracestate,
                                                 String timeoutMs) {
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
        return new RequestCorrelation(id, parent, state, validTimeout(timeoutMs), System.nanoTime());
    }

    /** A fresh request ID for a call that has no inbound request. */
    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    /** Trace ID (32 hex) of this context, or {@code null} without a {@code traceparent}. */
    public String traceId() {
        return traceparent == null ? null : traceId(traceparent);
    }

    /** Time since the request was received. */
    public Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - receivedAtNanos);
    }

    /** Whether the request ran longer than the caller said it would wait. */
    public boolean deadlineExceeded() {
        return timeoutMs != null && elapsed().compareTo(Duration.ofMillis(timeoutMs)) > 0;
    }

    /** Caller budget left for this request; {@code null} when the caller sent none; may be negative. */
    public Duration remaining() {
        return timeoutMs == null ? null : Duration.ofMillis(timeoutMs).minus(elapsed());
    }

    /**
     * The timeout for one outbound call or wait made while serving the current request:
     * {@code min(configured, caller budget left - reserve)}, or {@code configured} when there is no
     * current request or the caller sent no deadline (ADR-003 rule 3.2). May be zero or negative when
     * the budget is spent; callers treat that as "do not start".
     */
    public static Duration effectiveTimeout(Duration configured, Duration reserve) {
        RequestCorrelation current = CURRENT.get();
        Duration remaining = current == null ? null : current.remaining();
        if (remaining == null) {
            return configured;
        }
        Duration budget = remaining.minus(reserve);
        return budget.compareTo(configured) < 0 ? budget : configured;
    }

    private static Long validTimeout(String value) {
        if (value == null || !TIMEOUT.matcher(value.trim()).matches()) {
            return null;
        }
        long parsed = Long.parseLong(value.trim());
        return parsed >= 1 && parsed <= MAX_TIMEOUT_MS ? parsed : null;
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
