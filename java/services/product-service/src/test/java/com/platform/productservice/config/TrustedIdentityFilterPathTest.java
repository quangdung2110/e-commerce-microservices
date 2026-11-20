package com.platform.productservice.config;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bộ lọc phải quyết định theo đúng path mà Spring MVC dùng để route: đã decode và bỏ phần
 * {@code ;param}. Nếu so URI thô, {@code /%61pi/v1/...} hay {@code /internal/v1;x=1/...} né kiểm
 * quyền nhưng vẫn tới controller (cùng lỗi Inventory I3, ADR-028).
 *
 * <p>Dùng MockMvc standalone với controller giả ở các route thật để chứng minh request tới được
 * handler khi filter cho qua, nên status 200 nghĩa là "đã lọt".
 */
class TrustedIdentityFilterPathTest {

    private static final String IDENTITY_HEADER = "X-Service-Identity";

    @RestController
    static class StubController {
        @GetMapping("/api/v1/products")
        String adminRoute() {
            return "reached";
        }

        @GetMapping("/internal/v1/products/export")
        String export() {
            return "reached";
        }

        @GetMapping("/internal/v1/product-details/{slug}")
        String buyerRead(@PathVariable String slug) {
            return "reached";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new StubController())
                .addFilters(new TrustedIdentityFilter(new ProductProperties(), new ObjectMapper()))
                .build();
    }

    @Test
    @DisplayName("Admin route: path mã hoá hoặc có ;param vẫn bị kiểm role ADMIN")
    void encodedOrParameterisedAdminPathsStillRequireAdmin() throws Exception {
        for (String path : List.of("/api/v1/products", "/%61pi/v1/products", "/api/v1;x=1/products",
                "/api/v1/products;x=1", "/api/v1/%70roducts")) {
            int notAdmin = mvc.perform(MockMvcRequestBuilders.get(URI.create(path))
                    .header("X-User-Id", "user-1").header("X-User-Roles", "USER")).andReturn().getResponse().getStatus();
            assertThat(notAdmin).as("non-admin on " + path).isEqualTo(403);
            int anonymous = mvc.perform(MockMvcRequestBuilders.get(URI.create(path))).andReturn().getResponse().getStatus();
            assertThat(anonymous).as("anonymous on " + path).isEqualTo(401);
            int admin = mvc.perform(MockMvcRequestBuilders.get(URI.create(path))
                    .header("X-User-Id", "admin-1").header("X-User-Roles", "ADMIN")).andReturn().getResponse().getStatus();
            assertThat(admin).as("admin on " + path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("Internal route: path mã hoá hoặc có ;param vẫn bị kiểm service identity")
    void encodedOrParameterisedInternalPathsStillRequireServiceIdentity() throws Exception {
        for (String path : List.of("/internal/v1/products/export", "/%69nternal/v1/products/export",
                "/internal/v1;x=1/products/export", "/internal/v1/products/export;x=1")) {
            int anonymous = mvc.perform(MockMvcRequestBuilders.get(URI.create(path))).andReturn().getResponse().getStatus();
            assertThat(anonymous).as("anonymous on " + path).isEqualTo(401);
            int wrongGroup = mvc.perform(MockMvcRequestBuilders.get(URI.create(path))
                    .header(IDENTITY_HEADER, "product-detail-bff")).andReturn().getResponse().getStatus();
            assertThat(wrongGroup).as("buyer-read caller on export " + path).isEqualTo(403);
            int search = mvc.perform(MockMvcRequestBuilders.get(URI.create(path))
                    .header(IDENTITY_HEADER, "search-service")).andReturn().getResponse().getStatus();
            assertThat(search).as("search on export " + path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("Allowlist theo nhóm route dùng path đã decode: Search không đi vòng qua buyer-read")
    void groupSelectionUsesTheDecodedPath() throws Exception {
        for (String path : List.of("/internal/v1/product%2Ddetails/pixel", "/internal/v1/product-details;x=1/pixel")) {
            int search = mvc.perform(MockMvcRequestBuilders.get(URI.create(path))
                    .header(IDENTITY_HEADER, "search-service")).andReturn().getResponse().getStatus();
            assertThat(search).as("search on buyer-read " + path).isEqualTo(403);
            int bff = mvc.perform(MockMvcRequestBuilders.get(URI.create(path))
                    .header(IDENTITY_HEADER, "product-detail-bff")).andReturn().getResponse().getStatus();
            assertThat(bff).as("bff on buyer-read " + path).isEqualTo(200);
        }
    }
}
