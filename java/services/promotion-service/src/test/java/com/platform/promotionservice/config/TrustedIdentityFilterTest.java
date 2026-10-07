package com.platform.promotionservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.promotionservice.common.CallerContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** AuthZ ở biên: admin role, user identity, workload allowlist theo nhóm route. */
class TrustedIdentityFilterTest {

    private final TrustedIdentityFilter filter = new TrustedIdentityFilter(new PromotionProperties(), new ObjectMapper());

    @Test
    void adminRouteRequiresAdminRole() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/promotion-campaigns");
        request.addHeader("X-User-Id", UUID.randomUUID().toString());
        request.addHeader("X-User-Roles", "CUSTOMER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"FORBIDDEN\"");
    }

    @Test
    void adminRouteAcceptsAdmin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/promotion-campaigns");
        UUID userId = UUID.randomUUID();
        request.addHeader("X-User-Id", userId.toString());
        request.addHeader("X-User-Roles", "CUSTOMER, ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<UUID> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(CallerContext.current().userId());
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isEqualTo(userId);
    }

    @Test
    void customerRouteRequiresUserIdentity() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/promotion-discovery");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void internalRoutesAreAllowlistedPerGroup() throws Exception {
        MockHttpServletRequest calc = new MockHttpServletRequest("POST", "/internal/v1/promotion-discount-calculations");
        calc.addHeader("X-Service-Identity", "order-service");
        MockHttpServletResponse calcResponse = new MockHttpServletResponse();
        filter.doFilter(calc, calcResponse, (req, res) -> { });
        assertThat(calcResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest reserve = new MockHttpServletRequest("POST", "/internal/v1/promotion-allocation/reservations");
        reserve.addHeader("X-Service-Identity", "order-service");
        MockHttpServletResponse reserveResponse = new MockHttpServletResponse();
        filter.doFilter(reserve, reserveResponse, (req, res) -> { });
        assertThat(reserveResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest missing = new MockHttpServletRequest("POST", "/internal/v1/promotion-allocation/reservations");
        MockHttpServletResponse missingResponse = new MockHttpServletResponse();
        filter.doFilter(missing, missingResponse, (req, res) -> { });
        assertThat(missingResponse.getStatus()).isEqualTo(401);
    }
}
