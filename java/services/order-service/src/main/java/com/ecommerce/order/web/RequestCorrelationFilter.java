package com.ecommerce.order.web;

import com.ecommerce.order.common.RequestCorrelation;
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

/**
 * Publishes the normalized request ID, the inbound trace context and the caller deadline for the
 * request (contracts ADR-002 and ADR-003): into {@link RequestCorrelation} for outbound calls on this
 * thread, into the logging MDC as {@code request_id}/{@code trace_id}, and back to the caller as
 * {@code X-Request-Id}. A request that outlives the caller's {@code X-Request-Timeout-Ms} is counted
 * and logged; the work itself is never cancelled because of the header.
 *
 * <p>It runs before {@link IdentityFilter} so that a rejected caller is still correlated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    static final String MDC_REQUEST_ID = "request_id";
    static final String MDC_TRACE_ID = "trace_id";
    static final String DEADLINE_COUNTER = "http.server.caller_deadline_exceeded";
    private static final String CONTEXT_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".context";

    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);

    private final Supplier<MeterRegistry> meters;

    /** The registry is optional: web slice tests have none, and the counter is then skipped. */
    @Autowired
    public RequestCorrelationFilter(ObjectProvider<MeterRegistry> meters) {
        this.meters = meters::getIfAvailable;
    }

    public RequestCorrelationFilter(MeterRegistry meters) {
        this.meters = () -> meters;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RequestCorrelation correlation = (RequestCorrelation) request.getAttribute(CONTEXT_ATTRIBUTE);
        if (correlation == null) {
            correlation = RequestCorrelation.fromHeaders(
                request.getHeader(RequestCorrelation.REQUEST_ID_HEADER),
                request.getHeader(RequestCorrelation.TRACEPARENT_HEADER),
                request.getHeader(RequestCorrelation.TRACESTATE_HEADER),
                request.getHeader(RequestCorrelation.TIMEOUT_HEADER));
            request.setAttribute(CONTEXT_ATTRIBUTE, correlation);
        }
        RequestCorrelation.set(correlation);
        MDC.put(MDC_REQUEST_ID, correlation.requestId());
        if (correlation.traceId() != null) {
            MDC.put(MDC_TRACE_ID, correlation.traceId());
        }
        if (MDC.get("trace_id") == null && MDC.get("traceId") != null) { MDC.put("trace_id", MDC.get("traceId")); }
        response.setHeader(RequestCorrelation.REQUEST_ID_HEADER, correlation.requestId());
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
            // The first async dispatch exits before the response is complete. Keep the original
            // receivedAtNanos on the request and count only when the final dispatch completes.
            if (!request.isAsyncStarted() && correlation.deadlineExceeded()) {
                reportOverrun(request, correlation);
            }
            RequestCorrelation.clear();
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_TRACE_ID);
        }
    }

    private void reportOverrun(HttpServletRequest request, RequestCorrelation correlation) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = pattern == null ? "UNMATCHED" : pattern.toString();
        MeterRegistry registry = meters.get();
        if (registry != null) {
            registry.counter(DEADLINE_COUNTER, "route", route).increment();
        }
        log.warn("request outlived the caller deadline route={} elapsed_ms={} budget_ms={}", route,
                correlation.elapsed().toMillis(), correlation.timeoutMs());
    }
}
