package com.platform.promotionservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.promotionservice.common.CallerContext;
import com.platform.promotionservice.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Xác thực caller ở biên của service (docs/promotion/domain.md §9).
 *
 * <ul>
 *   <li>{@code /api/v1/admin/**}: trusted identity do Gateway inject + role ADMIN.</li>
 *   <li>{@code /api/v1/me/**}, {@code /api/v1/promotion-discovery}: trusted user identity.</li>
 *   <li>{@code /internal/v1/**}: service identity đã được mesh/ingress xác minh và đặt vào
 *       header cấu hình; allowlist tách theo nhóm route (Checkout → calculations,
 *       Order/Temporal → allocation), khớp hai security scheme trong OpenAPI v1.</li>
 * </ul>
 *
 * <p>Header trusted chỉ hợp lệ vì các endpoint này không được expose trực tiếp
 * ra ngoài; Gateway strip mọi identity header do client tự gửi.
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class TrustedIdentityFilter extends OncePerRequestFilter {

    private static final String ADMIN_PATH = "/api/v1/admin/";
    private static final String USER_PATHS_PREFIX = "/api/v1/";
    private static final String INTERNAL_PATH = "/internal/v1/";
    private static final String CALCULATION_PATH = "/internal/v1/promotion-discount-calculations";
    private static final String ALLOCATION_PATH = "/internal/v1/promotion-allocation/";

    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final PromotionProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String path = request.getRequestURI();
            if (path.startsWith(INTERNAL_PATH)) {
                if (!authorizeInternal(path, request, response)) {
                    return;
                }
            } else if (path.startsWith(ADMIN_PATH)) {
                if (!authorizeUser(request, response, true)) {
                    return;
                }
            } else if (path.startsWith(USER_PATHS_PREFIX)) {
                if (!authorizeUser(request, response, false)) {
                    return;
                }
            } else {
                CallerContext.set(null, List.of(), null,
                        request.getHeader(REQUEST_ID_HEADER), request.getHeader(TRACEPARENT_HEADER));
            }
            chain.doFilter(request, response);
        } finally {
            CallerContext.clear();
        }
    }

    private boolean authorizeUser(HttpServletRequest request, HttpServletResponse response,
                                  boolean requireAdmin) throws IOException {
        PromotionProperties.Admin admin = properties.getAdmin();
        String userIdHeader = request.getHeader(admin.getUserIdHeader());
        if (userIdHeader == null || userIdHeader.isBlank()) {
            reject(response, request, ErrorCode.UNAUTHENTICATED, "Missing trusted identity from the API Gateway");
            return false;
        }
        UUID userId;
        try {
            userId = UUID.fromString(userIdHeader.trim());
        } catch (IllegalArgumentException e) {
            reject(response, request, ErrorCode.UNAUTHENTICATED, "Trusted identity is not a valid user id");
            return false;
        }
        String rolesHeader = request.getHeader(admin.getRolesHeader());
        List<String> roles = rolesHeader == null ? List.of()
                : Arrays.stream(rolesHeader.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (requireAdmin && roles.stream().noneMatch(role -> role.equalsIgnoreCase(admin.getRequiredRole()))) {
            reject(response, request, ErrorCode.FORBIDDEN,
                    "Promotion administration requires the " + admin.getRequiredRole() + " role");
            return false;
        }
        CallerContext.set(userId, roles, null,
                request.getHeader(REQUEST_ID_HEADER), request.getHeader(TRACEPARENT_HEADER));
        return true;
    }

    private boolean authorizeInternal(String path, HttpServletRequest request,
                                      HttpServletResponse response) throws IOException {
        PromotionProperties.Internal internal = properties.getInternal();
        String identity = request.getHeader(internal.getIdentityHeader());
        if (identity == null || identity.isBlank()) {
            reject(response, request, ErrorCode.UNAUTHENTICATED, "Missing verified service identity");
            return false;
        }
        if (allowedClientsFor(path).stream().noneMatch(identity::equalsIgnoreCase)) {
            reject(response, request, ErrorCode.FORBIDDEN, "Service identity is not allowed on this internal route");
            return false;
        }
        CallerContext.set(null, List.of(), identity.trim(),
                request.getHeader(REQUEST_ID_HEADER), request.getHeader(TRACEPARENT_HEADER));
        return true;
    }

    /**
     * Route internal không thuộc nhóm nào trả list rỗng, tức là bị từ chối: thêm
     * endpoint internal mới phải kèm quyết định về caller.
     */
    private List<String> allowedClientsFor(String path) {
        PromotionProperties.Internal internal = properties.getInternal();
        if (path.equals(CALCULATION_PATH)) {
            return internal.getCalculationClients();
        }
        if (path.startsWith(ALLOCATION_PATH)) {
            return internal.getAllocationClients();
        }
        return List.of();
    }

    private void reject(HttpServletResponse response, HttpServletRequest request,
                        ErrorCode code, String detail) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://contracts.ecommerce.local/problems/"
                + code.name().toLowerCase().replace('_', '-'));
        problem.put("title", code.title());
        problem.put("status", code.status().value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        problem.put("code", code.name());
        problem.put("retryable", code.retryable());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
