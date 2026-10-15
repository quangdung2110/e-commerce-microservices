package com.ecommerce.order.web;

import com.ecommerce.order.common.RequestCorrelation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Publishes the normalized request ID and the inbound trace context for the request (contracts
 * ADR-002): into {@link RequestCorrelation} for outbound calls on this thread, into the logging MDC
 * as {@code request_id}/{@code trace_id}, and back to the caller as {@code X-Request-Id}.
 *
 * <p>It runs before {@link IdentityFilter} so that a rejected caller is still correlated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    static final String MDC_REQUEST_ID = "request_id";
    static final String MDC_TRACE_ID = "trace_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RequestCorrelation correlation = RequestCorrelation.fromHeaders(
                request.getHeader(RequestCorrelation.REQUEST_ID_HEADER),
                request.getHeader(RequestCorrelation.TRACEPARENT_HEADER),
                request.getHeader(RequestCorrelation.TRACESTATE_HEADER));
        RequestCorrelation.set(correlation);
        MDC.put(MDC_REQUEST_ID, correlation.requestId());
        if (correlation.traceId() != null) {
            MDC.put(MDC_TRACE_ID, correlation.traceId());
        }
        response.setHeader(RequestCorrelation.REQUEST_ID_HEADER, correlation.requestId());
        try {
            chain.doFilter(request, response);
        } finally {
            RequestCorrelation.clear();
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_TRACE_ID);
        }
    }
}
