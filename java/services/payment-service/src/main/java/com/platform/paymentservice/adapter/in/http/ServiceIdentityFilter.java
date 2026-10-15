package com.platform.paymentservice.adapter.in.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Caller check for the Order-facing routes (contracts ADR-004, Payment ADR-026): {@code /v1/payments/**}
 * except the Stripe webhook, and {@code /v1/refunds/**}. The caller names itself in
 * {@code X-Service-Identity}: a missing value is {@code 401 UNAUTHENTICATED}, a name outside
 * {@code payment.identity.allowed-callers} is {@code 403 FORBIDDEN}. The mesh is the real boundary and the
 * header is attribution, so this is the second layer. The webhook is public (Stripe, verified by signature)
 * and the Finance Ops routes under {@code /internal/v1/payment} have their own callers; neither is checked here.
 *
 * <p>The decision is made on the decoded path without {@code ;param} so encoding cannot skip it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ServiceIdentityFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Service-Identity";
    private static final String WEBHOOK_PREFIX = "/v1/payments/webhook";
    private static final String MISSING_CORRELATION = "missing-correlation-id";

    private final List<String> allowedCallers;
    private final ObjectMapper mapper;

    public ServiceIdentityFilter(@Value("${payment.identity.allowed-callers:order-service}") List<String> allowedCallers,
                                 ObjectMapper mapper) {
        this.allowedCallers = allowedCallers.stream().map(String::trim).filter(name -> !name.isEmpty()).toList();
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (!orderFacing(path)) {
            chain.doFilter(request, response);
            return;
        }
        String caller = request.getHeader(HEADER);
        caller = caller == null ? "" : caller.trim();
        if (caller.isEmpty()) {
            reject(request, response, 401, "UNAUTHENTICATED");
        } else if (!allowedCallers.contains(caller)) {
            reject(request, response, 403, "FORBIDDEN");
        } else {
            chain.doFilter(request, response);
        }
    }

    static boolean orderFacing(String path) {
        if (path.equals(WEBHOOK_PREFIX) || path.startsWith(WEBHOOK_PREFIX + "/")) {
            return false;
        }
        return path.equals("/v1/payments") || path.startsWith("/v1/payments/")
                || path.equals("/v1/refunds") || path.startsWith("/v1/refunds/");
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String code)
            throws IOException {
        String correlation = request.getHeader("X-Correlation-Id");
        String safe = correlation == null || correlation.isBlank() || correlation.length() > 128
                ? MISSING_CORRELATION : correlation;
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("code", code);
        problem.put("retryable", false);
        problem.put("correlation_id", safe);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("X-Correlation-Id", safe);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
