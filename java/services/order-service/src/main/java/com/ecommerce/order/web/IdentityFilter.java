package com.ecommerce.order.web;

import com.ecommerce.order.common.ErrorCode;
import com.ecommerce.order.common.Ids;
import com.ecommerce.order.config.OrderProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Caller authentication at the service boundary (domain.md §6):
 * <ul>
 *   <li>{@code /api/v1/orders/**}, {@code /api/v1/order-operations/**}: Buyer via the
 *       Gateway-injected {@code X-User-Id} (UUID).</li>
 *   <li>{@code /api/v1/admin/**}: {@code X-User-Roles} must contain ADMIN.</li>
 *   <li>{@code /internal/v1/checkout-order-submissions**}: mesh principal checkout-service;
 *       {@code X-Service-Identity} is checked as defense-in-depth.</li>
 *   <li>{@code /internal/v1/orders/**}: finance-ops/accounting-ops plus {@code X-Actor-Id}.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IdentityFilter extends OncePerRequestFilter {

    private final OrderProperties.Identity identity;
    private final Problems problems;
    private final ObjectMapper mapper;

    public IdentityFilter(OrderProperties properties, Problems problems, ObjectMapper mapper) {
        this.identity = properties.identity();
        this.problems = problems;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The decoded path without ';' parameters is what Spring MVC routes on. Matching the raw URI
        // would let "/api/v1/%61dmin/..." or "/api/v1/admin;x=1/..." reach a controller without the
        // role check (Inventory audit I3, ADR-028).
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        try {
            if (path.startsWith("/internal/v1/checkout-order-submissions")) {
                if (!service(request, response, path, identity.checkoutClients(), Caller.Kind.CHECKOUT, false)) {
                    return;
                }
            } else if (path.startsWith("/internal/v1/orders/")) {
                if (!service(request, response, path, identity.financeClients(), Caller.Kind.FINANCE,
                        "POST".equals(request.getMethod()))) {
                    return;
                }
            } else if (path.startsWith("/api/v1/admin/")) {
                UUID user = Ids.parseUuid(trim(request.getHeader(identity.userIdHeader())));
                if (user == null) {
                    reject(response, path, ErrorCode.UNAUTHENTICATED, "Missing trusted identity from the API Gateway");
                    return;
                }
                if (!hasAdminRole(request.getHeader(identity.rolesHeader()))) {
                    reject(response, path, ErrorCode.FORBIDDEN, "Order administration requires the "
                            + identity.adminRole() + " role");
                    return;
                }
                Caller.set(new Caller(Caller.Kind.ADMIN, user.toString(), user, user));
            } else if (path.startsWith("/api/v1/orders") || path.startsWith("/api/v1/order-operations")) {
                UUID user = Ids.parseUuid(trim(request.getHeader(identity.userIdHeader())));
                if (user == null) {
                    reject(response, path, ErrorCode.UNAUTHENTICATED, "Missing trusted identity from the API Gateway");
                    return;
                }
                Caller.set(new Caller(Caller.Kind.BUYER, user.toString(), user, user));
            }
            chain.doFilter(request, response);
        } finally {
            Caller.clear();
        }
    }

    private boolean service(HttpServletRequest request, HttpServletResponse response, String path,
                            List<String> allowed, Caller.Kind kind, boolean requireActor) throws IOException {
        String service = trim(request.getHeader(identity.serviceHeader()));
        if (service == null) {
            reject(response, path, ErrorCode.UNAUTHENTICATED, "Missing " + identity.serviceHeader());
            return false;
        }
        if (!allowed.contains(service)) {
            reject(response, path, ErrorCode.FORBIDDEN, "Workload identity is not allowed on this route");
            return false;
        }
        UUID actor = Ids.parseUuid(trim(request.getHeader(identity.actorHeader())));
        if (requireActor && actor == null) {
            reject(response, path, ErrorCode.INVALID_REQUEST, identity.actorHeader() + " must be a UUID");
            return false;
        }
        Caller.set(new Caller(kind, service, null, actor));
        return true;
    }

    private boolean hasAdminRole(String roles) {
        return roles != null && Arrays.stream(roles.split(",")).map(String::trim).anyMatch(identity.adminRole()::equals);
    }

    private void reject(HttpServletResponse response, String path, ErrorCode code, String detail) throws IOException {
        response.setStatus(code.status());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problems.body(code, detail, List.of(), path, null));
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
