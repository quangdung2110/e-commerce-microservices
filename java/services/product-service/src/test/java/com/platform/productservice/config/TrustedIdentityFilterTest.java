package com.platform.productservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quyền của {@code /internal/v1/**} tách theo nhóm route, khớp hai security
 * scheme trong contracts/http/product/v1/openapi.yaml: {@code searchServiceMtls}
 * cho reindex và {@code productDetailBffMtls} cho buyer-read (ADR-015).
 *
 * <p>Hai case chéo là lý do tồn tại của test này: Product Detail BFF không được
 * đọc export chứa cả Product chưa công bố, và Search không được đi vòng qua
 * buyer-read.
 */
class TrustedIdentityFilterTest {

    private static final String IDENTITY_HEADER = "X-Service-Identity";
    private static final String SEARCH = "search-service";
    private static final String BFF = "product-detail-bff";

    private static final String PRODUCT_EXPORT = "/internal/v1/products/export";
    private static final String CATEGORY_EXPORT = "/internal/v1/categories/export";
    private static final String SNAPSHOT =
            "/internal/v1/products/0192f0c0-0000-7000-8000-000000000000/snapshot";
    private static final String BUYER_READ = "/internal/v1/product-details/pixel";

    private TrustedIdentityFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TrustedIdentityFilter(new ProductProperties(), new ObjectMapper());
    }

    @Test
    @DisplayName("Search đọc được cả ba route reindex")
    void searchServiceReachesReindexRoutes() {
        assertThat(statusOf(PRODUCT_EXPORT, SEARCH)).isEqualTo(HttpStatus.OK.value());
        assertThat(statusOf(CATEGORY_EXPORT, SEARCH)).isEqualTo(HttpStatus.OK.value());
        assertThat(statusOf(SNAPSHOT, SEARCH)).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("Product Detail BFF đọc được buyer-read")
    void bffReachesBuyerRead() {
        assertThat(statusOf(BUYER_READ, BFF)).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("Product Detail BFF không đọc được export và snapshot")
    void bffCannotReachReindexRoutes() {
        assertThat(statusOf(PRODUCT_EXPORT, BFF)).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(statusOf(CATEGORY_EXPORT, BFF)).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(statusOf(SNAPSHOT, BFF)).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("Search không đi vòng qua buyer-read")
    void searchCannotReachBuyerRead() {
        assertThat(statusOf(BUYER_READ, SEARCH)).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("Thiếu service identity là 401, không phải 403")
    void missingIdentityIsUnauthenticated() {
        MockHttpServletResponse response = invoke(BUYER_READ, null);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
    }

    @Test
    @DisplayName("Identity lạ bị từ chối ở mọi nhóm route")
    void unknownIdentityIsRejected() {
        assertThat(statusOf(BUYER_READ, "cart-service")).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(statusOf(PRODUCT_EXPORT, "cart-service")).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("Route internal chưa được gán nhóm thì không ai vào được")
    void unmappedInternalRouteIsClosedByDefault() {
        assertThat(statusOf("/internal/v1/something-new", SEARCH))
                .isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(statusOf("/internal/v1/something-new", BFF))
                .isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("Filter không đụng tới route ngoài /api/v1 và /internal/v1")
    void otherRoutesPassThrough() {
        assertThat(statusOf("/actuator/health", null)).isEqualTo(HttpStatus.OK.value());
    }

    private int statusOf(String path, String identity) {
        return invoke(path, identity).getStatus();
    }

    /** Chain chỉ chạy khi filter cho qua, nên status 200 nghĩa là được ủy quyền. */
    private MockHttpServletResponse invoke(String path, String identity) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        if (identity != null) {
            request.addHeader(IDENTITY_HEADER, identity);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            filter.doFilter(request, response, new MockFilterChain());
        } catch (Exception e) {
            throw new IllegalStateException("filter threw for " + path, e);
        }
        return response;
    }
}
