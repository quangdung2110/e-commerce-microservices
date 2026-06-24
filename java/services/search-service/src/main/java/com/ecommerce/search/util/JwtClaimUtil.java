package com.ecommerce.search.util;

import com.ecommerce.search.config.SearchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtClaimUtil {

    private final ObjectMapper objectMapper;
    private final SearchProperties properties;

    /**
     * Extract user_id from X-User-Claims header.
     */
    public String getUserId(HttpServletRequest req) {
        return extractClaim(req, "user_id");
    }

    /**
     * Extract role (BUYER | ADMIN) from X-User-Claims header.
     */
    public String getRole(HttpServletRequest req) {
        return extractClaim(req, "role");
    }

    /**
     * Extract request_id from X-User-Claims header.
     */
    public String getRequestId(HttpServletRequest req) {
        return extractClaim(req, "request_id");
    }

    /**
     * Extract session_id from X-User-Claims header.
     */
    public String getSessionId(HttpServletRequest req) {
        return extractClaim(req, "session_id");
    }

    /**
     * Populate MDC context with request_id and user_id from the X-User-Claims header.
     * Call early in the filter chain so downstream components have context.
     */
    public void populateMdcContext(HttpServletRequest req) {
        String requestId = getRequestId(req);
        if (requestId != null) {
            MDC.put("request_id", requestId);
        }
        String userId = getUserId(req);
        if (userId != null) {
            MDC.put("user_id", userId);
        }
    }

    private String extractClaim(HttpServletRequest req, String claimName) {
        try {
            String header = req.getHeader(properties.getJwt().getClaimsHeader());
            if (header == null || header.isBlank()) return null;
            Map<String, Object> claims = objectMapper.readValue(header, Map.class);
            Object value = claims.get(claimName);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.warn("Failed to parse JWT claim: {}", claimName, e);
            return null;
        }
    }
}
