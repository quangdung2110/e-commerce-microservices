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
 * Caller check per route group (contracts ADR-004, Payment ADR-026 and ADR-028). The caller names itself in
 * {@code X-Service-Identity}: a missing value is {@code 401 UNAUTHENTICATED}, a name outside the group's
 * allowlist is {@code 403 FORBIDDEN}.
 * <ul>
 *   <li>Order-facing: {@code /v1/payments/**} except the Stripe webhook, and {@code /v1/refunds/**};
 *       {@code payment.identity.allowed-callers}.</li>
 *   <li>Finance Ops: {@code /internal/v1/payment/**}; {@code payment.identity.operations-callers}. The
 *       maker-checker headers are checked afterwards by {@code PaymentOperationsContractInterceptor}.</li>
 *   <li>Any other {@code /internal/**} path belongs to no group and rejects every caller.</li>
 * </ul>
 * The mesh is the real boundary and the header is attribution, so this is the second layer. The webhook is
 * public (Stripe, verified by signature) and is not checked. An empty allowlist rejects every caller.
 *
 * <p>The decision is made on the decoded path without {@code ;param} so encoding cannot skip it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ServiceIdentityFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Service-Identity";
    private static final String WEBHOOK_PREFIX = "/v1/payments/webhook";
    private static final String OPERATIONS_PREFIX = "/internal/v1/payment";
    private static final String MISSING_CORRELATION = "missing-correlation-id";

    private final List<String> allowedCallers;
    private final List<String> operationsCallers;
    private final ObjectMapper mapper;

    public ServiceIdentityFilter(@Value("${payment.identity.allowed-callers:order-service}") List<String> allowedCallers,
                                 @Value("${payment.identity.operations-callers:}") List<String> operationsCallers,
                                 ObjectMapper mapper) {
        this.allowedCallers = clean(allowedCallers);
        this.operationsCallers = clean(operationsCallers);
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        List<String> callers = callersFor(path);
        if (callers == null) {
            chain.doFilter(request, response);
            return;
        }
        String caller = request.getHeader(HEADER);
        caller = caller == null ? "" : caller.trim();
        if (caller.isEmpty()) {
            reject(request, response, 401, "UNAUTHENTICATED");
        } else if (!callers.contains(caller)) {
            reject(request, response, 403, "FORBIDDEN");
        } else {
            chain.doFilter(request, response);
        }
    }

    /** The allowlist of the route group, an empty list for an internal path of no group, null when unchecked. */
    List<String> callersFor(String path) {
        if (orderFacing(path)) {
            return allowedCallers;
        }
        if (under(path, OPERATIONS_PREFIX)) {
            return operationsCallers;
        }
        if (under(path, "/internal")) {
            return List.of();
        }
        return null;
    }

    static boolean orderFacing(String path) {
        if (under(path, WEBHOOK_PREFIX)) {
            return false;
        }
        return under(path, "/v1/payments") || under(path, "/v1/refunds");
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static List<String> clean(List<String> names) {
        return names.stream().map(String::trim).filter(name -> !name.isEmpty()).toList();
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
