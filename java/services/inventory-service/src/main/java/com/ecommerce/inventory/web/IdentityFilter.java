package com.ecommerce.inventory.web;

import com.ecommerce.inventory.common.ErrorCode;
import com.ecommerce.inventory.config.InventoryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Caller authorization at the service boundary.
 *
 * <ul>
 *   <li>{@code /internal/inventory/v1/**} and {@code /exports/inventory/v1/**}: the mesh
 *       enforces workload identity; {@code X-Service-Identity} is checked against a
 *       per-path allowlist as defense-in-depth (mesh runtime contract §6, ADR-027).</li>
 *   <li>{@code /api/v1/admin/inventory/**}: gateway-injected {@code X-User-Id} and
 *       {@code X-User-Roles} containing {@code ADMIN} (ADR-024). The gateway strips any
 *       client-supplied identity header.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IdentityFilter extends OncePerRequestFilter {

    static final String PREVIEW_PATH = "/internal/inventory/v1/allocation-previews";
    static final String AVAILABILITY_READ_PATH = "/internal/inventory/v1/sku-availabilities:batch-get";
    static final String WORKFLOW_PREFIX = "/internal/inventory/v1/";
    static final String EXPORT_PREFIX = "/exports/inventory/v1/";
    static final String ADMIN_PREFIX = "/api/v1/admin/inventory";

    private final InventoryProperties.Identity identity;
    private final Problems problems;
    private final ObjectMapper objectMapper;

    public IdentityFilter(InventoryProperties properties, Problems problems, ObjectMapper objectMapper) {
        this.identity = properties.identity();
        this.problems = problems;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String correlationId = request.getHeader("X-Correlation-ID");
        String causationId = request.getHeader("X-Causation-ID");
        try {
            // Provisional context so that a rejection below still carries correlation/instance.
            RequestContext.set(new RequestContext(null, null, null, null, null, correlationId, causationId, path));
            List<String> allowed = allowedServices(path);
            if (allowed != null) {
                String service = trimToNull(request.getHeader(identity.serviceHeader()));
                if (service == null) {
                    reject(response, ErrorCode.UNAUTHENTICATED, "Missing " + identity.serviceHeader());
                    return;
                }
                if (allowed.stream().noneMatch(service::equals)) {
                    reject(response, ErrorCode.FORBIDDEN, "Service identity is not allowed on this route");
                    return;
                }
                RequestContext.set(new RequestContext(service, "SERVICE", service, null, null,
                        correlationId, causationId, path));
            } else if (path.equals(ADMIN_PREFIX) || path.startsWith(ADMIN_PREFIX + "/")) {
                String userId = trimToNull(request.getHeader(identity.userIdHeader()));
                if (userId == null || userId.length() > 128) {
                    reject(response, ErrorCode.UNAUTHENTICATED, "Missing trusted identity from the API Gateway");
                    return;
                }
                String roles = request.getHeader(identity.rolesHeader());
                boolean admin = roles != null && Arrays.stream(roles.split(","))
                        .map(String::trim)
                        .anyMatch(role -> role.equals(identity.adminRole()));
                if (!admin) {
                    reject(response, ErrorCode.FORBIDDEN, "Inventory administration requires the "
                            + identity.adminRole() + " role");
                    return;
                }
                RequestContext.set(new RequestContext(RequestContext.ADMIN_NAMESPACE, "USER", userId,
                        identity.adminRole(), trimToNull(request.getHeader(identity.sessionHeader())),
                        correlationId, causationId, path));
            }
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    /** Null when the path is not a service-to-service surface. */
    private List<String> allowedServices(String path) {
        if (path.equals(PREVIEW_PATH)) {
            return identity.previewClients();
        }
        if (path.equals(AVAILABILITY_READ_PATH)) {
            return identity.availabilityReadClients();
        }
        if (path.startsWith(WORKFLOW_PREFIX)) {
            return identity.workflowClients();
        }
        if (path.startsWith(EXPORT_PREFIX)) {
            return identity.exportClients();
        }
        return null;
    }

    private void reject(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        response.setStatus(code.status());
        response.setContentType(Problems.PROBLEM_JSON.toString());
        objectMapper.writeValue(response.getOutputStream(), problems.body(code, detail, List.of(), null));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
