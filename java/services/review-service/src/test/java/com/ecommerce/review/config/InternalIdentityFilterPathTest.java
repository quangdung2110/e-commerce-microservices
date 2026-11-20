package com.ecommerce.review.config;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The filter must decide on the path Spring MVC routes on: decoded, without {@code ;param}. With the
 * raw URI, {@code /internal/v1;x=1/...} and {@code /%69nternal/v1/...} are not "internal" to the filter,
 * so no service identity is required, yet both reach the controller (same defect as Inventory I3, ADR-028).
 *
 * <p>Standalone MockMvc with stub controllers on the real routes: status 200 means the request got
 * through to the handler. No database is needed, so this runs with the unit tests.
 */
class InternalIdentityFilterPathTest {

    private static final String PDP = "product-detail-bff";
    private static final String EXPORT = "search-service";

    @RestController
    static class StubController {
        @GetMapping("/internal/v1/reviews/products/{productId}/pdp-summary")
        String pdp(@PathVariable String productId) {
            return "reached";
        }

        @GetMapping("/internal/v1/reviews/rating-aggregate-exports")
        String export() {
            return "reached";
        }

        @GetMapping("/api/v1/products/{productId}/reviews")
        String publicList(@PathVariable String productId) {
            return "reached";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ReviewProperties properties = mock(ReviewProperties.class);
        when(properties.gateway()).thenReturn(new ReviewProperties.Gateway(
                "X-User-Id", "X-User-Roles", "X-Request-Id", "ADMIN"));
        when(properties.internal()).thenReturn(new ReviewProperties.Internal(
                List.of("product-detail-bff"), List.of("search-service")));
        mvc = MockMvcBuilders.standaloneSetup(new StubController())
                .addFilters(new InternalIdentityFilter(properties, new ObjectMapper()))
                .build();
    }

    private int status(String path, String authorization) throws Exception {
        var request = MockMvcRequestBuilders.get(URI.create(path));
        if (authorization != null) {
            request.header("X-Service-Identity", authorization);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("PDP route: path mã hoá hoặc có ;param vẫn cần X-Service-Identity của nhóm PDP")
    void encodedOrParameterisedPdpPathsStillRequireThePdpCaller() throws Exception {
        for (String path : List.of("/internal/v1/reviews/products/p1/pdp-summary",
                "/%69nternal/v1/reviews/products/p1/pdp-summary", "/internal/v1;x=1/reviews/products/p1/pdp-summary",
                "/internal/v1/reviews/products/p1/pdp-summary;x=1", "/internal/v1/review%73/products/p1/pdp-summary",
                "/internal/v1/reviews;x=1/products/p1/pdp-summary")) {
            assertThat(status(path, null)).as("anonymous on " + path).isEqualTo(401);
            assertThat(status(path, EXPORT)).as("export caller on pdp " + path).isEqualTo(403);
            assertThat(status(path, PDP)).as("pdp caller on pdp " + path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("Export route: path mã hoá hoặc có ;param vẫn cần X-Service-Identity của nhóm export")
    void encodedOrParameterisedExportPathsStillRequireTheExportCaller() throws Exception {
        for (String path : List.of("/internal/v1/reviews/rating-aggregate-exports",
                "/%69nternal/v1/reviews/rating-aggregate-exports", "/internal/v1;x=1/reviews/rating-aggregate-exports",
                "/internal/v1/reviews/rating%2Daggregate-exports", "/internal/v1/reviews/rating-aggregate-exports;x=1")) {
            assertThat(status(path, null)).as("anonymous on " + path).isEqualTo(401);
            assertThat(status(path, PDP)).as("pdp caller on export " + path).isEqualTo(403);
            assertThat(status(path, EXPORT)).as("export caller on export " + path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("Route công khai không bị đòi X-Service-Identity")
    void publicRoutesPassThrough() throws Exception {
        assertThat(status("/api/v1/products/p1/reviews", null)).isEqualTo(200);
    }
}
