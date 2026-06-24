package com.platform.inventoryservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class InternalServiceTokenValidator extends OncePerRequestFilter {

    private static final String HEADER = "X-Internal-Service";
    private static final String ORDER_SERVICE = "order-service";

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        if (!req.getRequestURI().contains("/refund-expectations")) {
            chain.doFilter(req, resp);
            return;
        }

        String serviceId = req.getHeader(HEADER);
        if (!ORDER_SERVICE.equals(serviceId)) {
            writeError(resp, 401, "MISSING_INTERNAL_SERVICE",
                       "Missing or invalid X-Internal-Service header");
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            writeError(resp, 401, "UNAUTHENTICATED", "Missing or invalid JWT token");
            return;
        }

        chain.doFilter(req, resp);
    }

    private void writeError(HttpServletResponse resp, int status, String code, String message)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.getWriter().write(objectMapper.writeValueAsString(
            Map.of("error", code, "message", message)));
    }
}
