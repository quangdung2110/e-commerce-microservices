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
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Xác thực caller ở biên của service.
 *
 * <p>{@code /api/v1/**} chỉ nhận trusted identity do API Gateway inject sau khi
 * verify JWT, và yêu cầu role ADMIN. {@code /internal/v1/**} dùng mTLS service
 * identity trên mạng nội bộ; service tin identity đã được ingress/mesh xác minh
 * và đặt vào header cấu hình.
 *
 * <p>Quyền internal tách theo nhóm route chứ không dùng một allowlist phẳng, vì
 * contracts/http/product/v1/openapi.yaml khai báo hai security scheme riêng:
 * {@code searchServiceMtls} cho reindex và {@code productDetailBffMtls} cho
 * buyer-read (ADR-015). Nhờ vậy Product Detail BFF không đọc được export chứa cả
 * Product {@code DRAFT}/{@code HIDDEN}, và Search không đi vòng qua buyer-read.
 * Trong scheme {@code searchServiceMtls}, Promotion chỉ được gọi snapshot một
 * Product, không được gọi export.
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

    /** Buyer-read cho Product Detail BFF (WF-PRODUCT-007). */
    private static final String BUYER_READ_PATH = "/internal/v1/product-details/";

    /** Snapshot một Product: Search rebuild document, Promotion validate trước khi lưu rule. */
    private static final Pattern SNAPSHOT_PATH = Pattern.compile("^/internal/v1/products/[^/]+/snapshot$");

    /** Export phục vụ Search full reindex (ADR-014). */
    private static final List<String> REINDEX_PATHS =
            List.of("/internal/v1/products/", "/internal/v1/categories/");

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
            // The decoded path without ';' parameters is what Spring MVC routes on. Matching the raw
            // URI would let "/%61pi/v1/..." or "/internal/v1;x=1/..." reach a controller unchecked.
            String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
            if (path.startsWith(ADMIN_PATH) && !authorizeAdmin(request, response)) {
                return;
            }
            if (path.startsWith(INTERNAL_PATH) && !authorizeInternal(path, request, response)) {
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

    private boolean authorizeInternal(String path,
                                      HttpServletRequest request,
                                      HttpServletResponse response) throws IOException {
        ProductProperties.Internal internal = properties.getInternal();
        String identity = request.getHeader(internal.getIdentityHeader());
        if (identity == null || identity.isBlank()) {
            reject(response, request, ErrorCode.UNAUTHENTICATED,
                    "Missing verified service identity");
            return false;
        }
        if (allowedClientsFor(path).stream().noneMatch(identity::equalsIgnoreCase)) {
            reject(response, request, ErrorCode.FORBIDDEN,
                    "Service identity is not allowed on this internal route");
            return false;
        }
        return true;
    }

    /**
     * Allowlist của nhóm route chứa {@code path}. Route nội bộ không thuộc nhóm
     * nào trả về list rỗng, tức là bị từ chối: thêm endpoint internal mới phải
     * kèm quyết định về caller thay vì thừa hưởng quyền của nhóm khác.
     */
    private List<String> allowedClientsFor(String path) {
        ProductProperties.Internal internal = properties.getInternal();
        if (path.startsWith(BUYER_READ_PATH)) {
            return internal.getBuyerReadClients();
        }
        if (SNAPSHOT_PATH.matcher(path).matches()) {
            return internal.getSnapshotClients();
        }
        if (REINDEX_PATHS.stream().anyMatch(path::startsWith)) {
            return internal.getReindexClients();
        }
        return List.of();
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
