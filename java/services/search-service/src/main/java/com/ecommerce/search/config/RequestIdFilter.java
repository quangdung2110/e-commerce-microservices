package com.ecommerce.search.config;

import com.ecommerce.search.common.RequestCorrelation;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.function.Supplier;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request one effective request id, puts it into the MDC so every log line on
 * the read path can be correlated ({@code docs/search/operations.md §4}), echoes it in the
 * response header and exposes it to the error handler as the Problem {@code trace_id}.
 *
 * <p>The id a caller sends is kept only when it is also a legal Problem {@code trace_id}
 * (16..64 characters of a conservative alphabet, {@code problem.schema.json}); anything else,
 * and the absence of one, is replaced by a generated UUID. The OpenAPI {@code X-Request-Id}
 * parameter allows 8..128 characters, so a short or long id from a client is never echoed into
 * a body that would then violate the shared Problem schema.
 *
 * <p>Distributed tracing is not in place yet; correlation by request id is the current
 * mechanism.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** Request attribute that carries the effective id to the error handler. */
    public static final String REQUEST_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".REQUEST_ID";

    private static final String MDC_KEY = "request_id";
    private static final String MDC_TRACE_ID = "trace_id";
    static final String DEADLINE_COUNTER = "http.server.caller_deadline_exceeded";
    private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9._:-]{16,64}");

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    private final SearchProperties properties;
    private final Supplier<MeterRegistry> meters;

    /** The registry is optional: web slice tests have none, and the counter is then skipped. */
    @Autowired
    public RequestIdFilter(SearchProperties properties, ObjectProvider<MeterRegistry> meters) {
        this.properties = properties;
        this.meters = meters::getIfAvailable;
    }

    public RequestIdFilter(SearchProperties properties, MeterRegistry meters) {
        this.properties = properties;
        this.meters = () -> meters;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(properties.gateway().requestIdHeader());
        String requestId = supplied != null && TRACE_ID.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
        // contracts ADR-002/003: the trace context and the caller deadline ride along with the id Search
        // already normalizes; the request id rule above stays Search's own because the Problem schema
        // limits trace_id to 16..64 characters.
        RequestCorrelation correlation = RequestCorrelation.fromHeaders(requestId,
                request.getHeader(RequestCorrelation.TRACEPARENT_HEADER),
                request.getHeader(RequestCorrelation.TRACESTATE_HEADER),
                request.getHeader(RequestCorrelation.TIMEOUT_HEADER));
        RequestCorrelation.set(correlation);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        MDC.put(MDC_KEY, requestId);
        if (correlation.traceId() != null) {
            MDC.put(MDC_TRACE_ID, correlation.traceId());
        }
        if (MDC.get("trace_id") == null && MDC.get("traceId") != null) { MDC.put("trace_id", MDC.get("traceId")); }
        response.setHeader(properties.gateway().requestIdHeader(), requestId);
        long accessStarted = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            Object accessPattern = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            MDC.put("operation", accessPattern == null ? "unmatched" : accessPattern.toString());
            MDC.put("method", request.getMethod());
            MDC.put("status", Integer.toString(response.getStatus()));
            MDC.put("duration_ms", Long.toString((System.nanoTime() - accessStarted) / 1_000_000));
            log.info("request completed");
            MDC.remove("operation");
            MDC.remove("method");
            MDC.remove("status");
            MDC.remove("duration_ms");
            if (correlation.deadlineExceeded()) {
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String route = pattern == null ? "UNMATCHED" : pattern.toString();
                MeterRegistry registry = meters.get();
                if (registry != null) {
                    registry.counter(DEADLINE_COUNTER, "route", route).increment();
                }
                log.warn("request outlived the caller deadline route={} elapsed_ms={} budget_ms={}", route,
                        correlation.elapsed().toMillis(), correlation.timeoutMs());
            }
            RequestCorrelation.clear();
            MDC.remove(MDC_KEY);
            MDC.remove(MDC_TRACE_ID);
        }
    }
}
