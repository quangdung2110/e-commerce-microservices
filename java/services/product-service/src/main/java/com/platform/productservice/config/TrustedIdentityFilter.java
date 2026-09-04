package com.platform.productservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.infrastructure.event.EventContext;
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
import java.util.Map;

/**
 * Xác thực caller ở biên của service.
 *
 * <p>{@code /api/v1/**} chỉ nhận trusted identity do API Gateway inject sau khi
 * verify JWT, và yêu cầu role ADMIN. {@code /internal/v1/**} chỉ dành cho
 * Search Service qua mTLS trên mạng nội bộ; service tin identity đã được
 * ingress/mesh xác minh và đặt vào header cấu hình.
 *
 * <p>Header trusted chỉ hợp lệ vì các endpoint này không được expose trực tiếp
 * ra ngoài (contracts/conventions/http.md).
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class TrustedIdentityFilter extends OncePerRequestFilter {

    private static final String ADMIN_PATH = "/api/v1/";
    private static final String INTERNAL_PATH = "/internal/v1/";
    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final ProductProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        EventContext.set(request.getHeader(TRACEPARENT_HEADER), request.getHeader(REQUEST_ID_HEADER));
        try {
            String path = request.getRequestURI();
            if (path.startsWith(ADMIN_PATH) && !authorizeAdmin(request, response)) {
                return;
            }
            if (path.startsWith(INTERNAL_PATH) && !authorizeInternal(request, response)) {
                return;
            }
            chain.doFilter(request, response);
        } finally {
            EventContext.clear();
        }
    }

    private boolean authorizeAdmin(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ProductProperties.Admin admin = properties.getAdmin();
        String userId = request.getHeader(admin.getUserIdHeader());
        if (userId == null || userId.isBlank()) {
            reject(response, request, ErrorCode.UNAUTHENTICATED,
                    "Missing trusted identity from the API Gateway");
            return false;
        }
        String roles = request.getHeader(admin.getRolesHeader());
        boolean isAdmin = roles != null && Arrays.stream(roles.split(","))
                .map(String::trim)
                .anyMatch(role -> role.equalsIgnoreCase(admin.getRequiredRole()));
        if (!isAdmin) {
            reject(response, request, ErrorCode.FORBIDDEN,
                    "Catalog administration requires the " + admin.getRequiredRole() + " role");
            return false;
        }
        return true;
    }

    private boolean authorizeInternal(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ProductProperties.Internal internal = properties.getInternal();
        String identity = request.getHeader(internal.getIdentityHeader());
        if (identity == null || identity.isBlank()) {
            reject(response, request, ErrorCode.UNAUTHENTICATED,
                    "Missing verified service identity");
            return false;
        }
        if (internal.getAllowedClients().stream().noneMatch(identity::equalsIgnoreCase)) {
            reject(response, request, ErrorCode.FORBIDDEN,
                    "Service identity is not allowed to read the catalog export");
            return false;
        }
        return true;
    }

    private void reject(HttpServletResponse response,
                        HttpServletRequest request,
                        ErrorCode code,
                        String detail) throws IOException {
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
