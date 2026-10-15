package com.ecommerce.review.config;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * Correlation context for every request, and the caller check for {@code /internal/v1/**}
 * (contracts ADR-004, Review ADR-015).
 *
 * <p>The caller names itself in {@code X-Service-Identity}: a missing value is {@code 401}, a name outside
 * the allowlist of the route group is {@code 403}. The internal routes are split into two groups,
 * matching the two callers the internal OpenAPI describes: the Product Detail BFF reads the PDP
 * summary and Search pages the rating export. Neither can reach the other's route, and a
 * new internal endpoint that belongs to no group is rejected rather than inheriting
 * someone else's permission. The mesh is the real boundary; the header is attribution.
 */
@Component
@Order(1)
public class InternalIdentityFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PREFIX = "/internal/v1/";
    private static final String PDP_PREFIX = "/internal/v1/reviews/products/";
    private static final String EXPORT_PREFIX = "/internal/v1/reviews/rating-aggregate-exports";
    static final String IDENTITY_HEADER = "X-Service-Identity";

    private final ReviewProperties properties;
    private final ObjectMapper objectMapper;

    public InternalIdentityFilter(ReviewProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        RequestContext.setCorrelationId(request.getHeader(properties.gateway().requestIdHeader()));
        try {
            // The decoded path without ';' parameters is what Spring MVC routes on. Matching the raw
            // URI would let "/internal/v1;x=1/..." or "/%69nternal/v1/..." reach a controller without
            // a service token.
            String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
            if (path.startsWith(INTERNAL_PREFIX) && !authorizeInternal(path, request, response)) {
                return;
            }
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    private boolean authorizeInternal(String path, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String caller = request.getHeader(IDENTITY_HEADER);
        caller = caller == null ? "" : caller.trim();
        if (caller.isEmpty()) {
            reject(response, request, ErrorCode.UNAUTHENTICATED, "Missing internal service identity");
            return false;
        }
        if (!allowedCallersFor(path).contains(caller)) {
            reject(response, request, ErrorCode.FORBIDDEN,
                    "Service identity is not allowed on this internal route");
            return false;
        }
        return true;
    }

    /**
     * The caller allowlist of the route group containing {@code path}. An internal route in no
     * group returns an empty list, which rejects every caller: adding an internal
     * endpoint has to come with a decision about who may call it.
     */
    private List<String> allowedCallersFor(String path) {
        if (path.startsWith(EXPORT_PREFIX)) {
            return properties.internal().exportCallers();
        }
        if (path.startsWith(PDP_PREFIX)) {
            return properties.internal().pdpCallers();
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
        problem.put("type", code.typeUri());
        problem.put("title", code.title());
        problem.put("status", code.status().value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        problem.put("code", code.name());
        problem.put("retryable", code.retryable());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
