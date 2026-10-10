package com.ecommerce.search.config;

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
    private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9._:-]{16,64}");

    private final SearchProperties properties;

    public RequestIdFilter(SearchProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(properties.gateway().requestIdHeader());
        String requestId = supplied != null && TRACE_ID.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(properties.gateway().requestIdHeader(), requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
