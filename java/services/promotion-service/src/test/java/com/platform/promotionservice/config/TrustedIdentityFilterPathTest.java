package com.platform.promotionservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bộ lọc phải quyết định theo đúng path mà Spring MVC dùng để route: đã decode và bỏ {@code ;param}.
 * So URI thô thì {@code /api/v1/admin;x=1/...} rơi xuống nhánh "user thường" (không cần role ADMIN) còn
 * {@code /%61pi/v1/admin/...} và {@code /internal/v1;x=1/...} không bị kiểm gì, nhưng vẫn tới controller
 * (cùng lỗi Inventory I3, ADR-028).
 *
 * <p>MockMvc standalone với controller giả ở các route thật: status 200 nghĩa là request đã lọt tới handler.
 */
class TrustedIdentityFilterPathTest {

    @RestController
    static class StubController {
        @GetMapping("/api/v1/admin/promotion-campaigns")
        String admin() {
            return "reached";
        }

        @GetMapping("/api/v1/promotion-discovery")
        String user() {
            return "reached";
        }

        @GetMapping("/internal/v1/promotion-discount-calculations")
        String calculation() {
            return "reached";
        }

        @GetMapping("/internal/v1/promotion-allocation/reservations")
        String allocation() {
            return "reached";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new StubController())
                .addFilters(new TrustedIdentityFilter(new PromotionProperties(), new ObjectMapper()))
                .build();
    }

    private int status(String path, String... headers) throws Exception {
        var request = MockMvcRequestBuilders.get(URI.create(path));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("Admin route: path mã hoá hoặc có ;param vẫn bị kiểm role ADMIN")
    void encodedOrParameterisedAdminPathsStillRequireAdmin() throws Exception {
        String user = UUID.randomUUID().toString();
        for (String path : List.of("/api/v1/admin/promotion-campaigns", "/%61pi/v1/admin/promotion-campaigns",
                "/api/v1/admin;x=1/promotion-campaigns", "/api/v1/%61dmin/promotion-campaigns",
                "/api/v1/admin/promotion-campaigns;x=1", "/api/v1;x=1/admin/promotion-campaigns")) {
            assertThat(status(path, "X-User-Id", user, "X-User-Roles", "CUSTOMER"))
                    .as("non-admin on " + path).isEqualTo(403);
            assertThat(status(path)).as("anonymous on " + path).isEqualTo(401);
            assertThat(status(path, "X-User-Id", user, "X-User-Roles", "ADMIN"))
                    .as("admin on " + path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("User route: path mã hoá hoặc có ;param vẫn cần trusted user identity")
    void encodedOrParameterisedUserPathsStillRequireIdentity() throws Exception {
        for (String path : List.of("/api/v1/promotion-discovery", "/%61pi/v1/promotion-discovery",
                "/api/v1;x=1/promotion-discovery")) {
            assertThat(status(path)).as("anonymous on " + path).isEqualTo(401);
            assertThat(status(path, "X-User-Id", UUID.randomUUID().toString()))
                    .as("user on " + path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("Internal route: path mã hoá hoặc có ;param vẫn bị kiểm allowlist theo nhóm")
    void encodedOrParameterisedInternalPathsStillUseTheAllowlist() throws Exception {
        for (String path : List.of("/internal/v1/promotion-discount-calculations",
                "/%69nternal/v1/promotion-discount-calculations", "/internal/v1;x=1/promotion-discount-calculations",
                "/internal/v1/promotion%2Ddiscount-calculations", "/internal/v1/promotion-discount-calculations;x=1")) {
            assertThat(status(path)).as("anonymous on " + path).isEqualTo(401);
            assertThat(status(path, "X-Service-Identity", "order-service"))
                    .as("order-service on calculation " + path).isEqualTo(403);
            assertThat(status(path, "X-Service-Identity", "checkout-service"))
                    .as("checkout-service on calculation " + path).isEqualTo(200);
        }
        for (String path : List.of("/internal/v1/promotion-allocation;x=1/reservations",
                "/internal/v1/promotion%2Dallocation/reservations")) {
            assertThat(status(path, "X-Service-Identity", "checkout-service"))
                    .as("checkout-service on allocation " + path).isEqualTo(403);
            assertThat(status(path, "X-Service-Identity", "order-service"))
                    .as("order-service on allocation " + path).isEqualTo(200);
        }
    }
}
