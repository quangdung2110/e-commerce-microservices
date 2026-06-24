package com.ecommerce.search.config;

import com.ecommerce.search.util.JwtClaimUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that populates the MDC (Mapped Diagnostic Context) with
 * {@code request_id} and {@code user_id} from the {@code X-User-Claims}
 * header (injected by the API Gateway).
 *
 * <p>This filter runs once per request at the highest precedence, ensuring
 * that structured logging context is available throughout the entire request
 * lifecycle. The MDC is cleared in the {@code finally} block to prevent
 * context leaking between requests.
 *
 * <p>Actuator endpoints (health probes) are skipped to avoid unnecessary
 * header parsing overhead and log noise.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class MdcPopulationFilter extends OncePerRequestFilter {

    private final JwtClaimUtil jwtClaimUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            jwtClaimUtil.populateMdcContext(request);
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/");
    }
}
