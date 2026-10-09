package com.ecommerce.order.web;

import com.ecommerce.order.config.OrderProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter must decide on the path Spring MVC routes on: decoded, without {@code ;param}. With the
 * raw URI, {@code /api/v1/admin;x=1/orders} matched no branch at all and {@code /api/v1/%61dmin/orders}
 * skipped the ADMIN check, yet both reached the controller (same defect as Inventory I3, ADR-028).
 *
 * <p>Standalone MockMvc with stub controllers on the real routes: status 200 means the request got
 * through to the handler. No database is needed, so this runs with the unit tests.
 */
class IdentityFilterPathTest {

    private static final String BUYER = "11111111-1111-4111-8111-111111111111";

    @RestController
    static class StubController {
        @GetMapping("/api/v1/admin/orders")
        String admin() {
            return "reached";
        }

        @GetMapping("/api/v1/orders/{orderId}")
        String buyer(@PathVariable String orderId) {
            return "reached";
        }

        @GetMapping("/internal/v1/checkout-order-submissions/{token}")
        String checkout(@PathVariable String token) {
            return "reached";
        }

        @GetMapping("/internal/v1/orders/{orderId}/financial-reconciliation-case")
        String finance(@PathVariable String orderId) {
            return "reached";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        OrderProperties.Identity identity = new OrderProperties.Identity("X-Service-Identity", "X-User-Id",
                "X-User-Roles", "X-Actor-Id", "ADMIN", List.of("checkout-service"),
                List.of("finance-ops", "accounting-ops"));
        OrderProperties properties = new OrderProperties(identity, null, null, null, null, null, null, null, null,
                null, null);
        ObjectMapper mapper = new ObjectMapper();
        mvc = MockMvcBuilders.standaloneSetup(new StubController())
                .addFilters(new IdentityFilter(properties, new Problems(mapper), mapper))
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
        for (String path : List.of("/api/v1/admin/orders", "/%61pi/v1/admin/orders", "/api/v1/admin;x=1/orders",
                "/api/v1/%61dmin/orders", "/api/v1/admin/orders;x=1", "/api/v1;x=1/admin/orders")) {
            assertThat(status(path, "X-User-Id", BUYER, "X-User-Roles", "USER"))
                    .as("non-admin on " + path).isEqualTo(403);
            assertThat(status(path)).as("anonymous on " + path).isEqualTo(401);
            assertThat(status(path, "X-User-Id", BUYER, "X-User-Roles", "USER,ADMIN"))
                    .as("admin on " + path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("Buyer route: path mã hoá hoặc có ;param vẫn cần trusted user identity")
    void encodedOrParameterisedBuyerPathsStillRequireIdentity() throws Exception {
        String order = UUID.randomUUID().toString();
        for (String path : List.of("/api/v1/orders/" + order, "/%61pi/v1/orders/" + order,
                "/api/v1;x=1/orders/" + order, "/api/v1/order%73/" + order)) {
            assertThat(status(path)).as("anonymous on " + path).isEqualTo(401);
            assertThat(status(path, "X-User-Id", BUYER)).as("buyer on " + path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("Internal route: path mã hoá hoặc có ;param vẫn bị kiểm allowlist theo nhóm")
    void encodedOrParameterisedInternalPathsStillUseTheAllowlist() throws Exception {
        for (String path : List.of("/internal/v1/checkout-order-submissions/tok",
                "/%69nternal/v1/checkout-order-submissions/tok", "/internal/v1;x=1/checkout-order-submissions/tok",
                "/internal/v1/checkout%2Dorder-submissions/tok")) {
            assertThat(status(path)).as("anonymous on " + path).isEqualTo(401);
            assertThat(status(path, "X-Service-Identity", "finance-ops"))
                    .as("finance-ops on checkout " + path).isEqualTo(403);
            assertThat(status(path, "X-Service-Identity", "checkout-service"))
                    .as("checkout-service on checkout " + path).isEqualTo(200);
        }
        for (String path : List.of("/internal/v1/orders/42/financial-reconciliation-case",
                "/internal/v1;x=1/orders/42/financial-reconciliation-case",
                "/%69nternal/v1/orders/42/financial-reconciliation-case",
                "/internal/v1/orders;x=1/42/financial-reconciliation-case")) {
            assertThat(status(path)).as("anonymous on " + path).isEqualTo(401);
            assertThat(status(path, "X-Service-Identity", "checkout-service"))
                    .as("checkout-service on finance " + path).isEqualTo(403);
            assertThat(status(path, "X-Service-Identity", "finance-ops"))
                    .as("finance-ops on finance " + path).isEqualTo(200);
        }
    }
}
