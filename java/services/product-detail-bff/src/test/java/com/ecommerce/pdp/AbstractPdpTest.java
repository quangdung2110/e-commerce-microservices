package com.ecommerce.pdp;

import com.ecommerce.pdp.resilience.Dependency;
import com.ecommerce.pdp.resilience.DependencyGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Nền chung: BFF chạy thật trên port ngẫu nhiên, ba dependency là một WireMock server trả
 * payload đúng contract. Tracing bật với sampling 1.0 để kiểm tra {@code traceparent} và
 * trace ID.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@org.springframework.boot.autoconfigure.ImportAutoConfiguration(exclude =
        org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration.class)
// Giá trị mặc định ở đây (không ở @DynamicPropertySource) để @TestPropertySource của lớp
// con ghi đè được: property động luôn thắng property tĩnh.
@TestPropertySource(properties = {
        "pdp.inventory.enabled=true",
        "pdp.media.cdn-base-url=" + AbstractPdpTest.CDN,
        "pdp.media.placeholder-url=" + AbstractPdpTest.PLACEHOLDER,
        "pdp.full-response-cache-control=" + AbstractPdpTest.FULL_CACHE_CONTROL,
        // Test thay stub Product giữa các test cùng slug: catalog cache (ADR-011) chỉ bật ở
        // các test của chính nó, mọi test khác kiểm hành vi một lời gọi Product mỗi request.
        "pdp.catalog-cache.enabled=false",
        "management.tracing.sampling.probability=1.0"
})
public abstract class AbstractPdpTest {

    protected static final String PRODUCT_PATH = "/internal/v1/product-details/smart-watch";
    protected static final String INVENTORY_PATH = "/internal/inventory/v1/sku-availabilities:batch-get";
    protected static final String REVIEW_PATH =
            "/internal/v1/reviews/products/" + Fixtures.PRODUCT_ID + "/pdp-summary";
    protected static final String CDN = "https://cdn.test/media";
    protected static final String PLACEHOLDER = "https://cdn.test/placeholder.png";
    protected static final String FULL_CACHE_CONTROL = "public, max-age=30";

    protected static final WireMockServer UPSTREAMS = new WireMockServer(options().dynamicPort());
    private static final ObjectMapper READER = new ObjectMapper();

    static {
        UPSTREAMS.start();
    }

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        String baseUrl = "http://localhost:" + UPSTREAMS.port();
        registry.add("pdp.product.base-url", () -> baseUrl);
        registry.add("pdp.inventory.base-url", () -> baseUrl);
        registry.add("pdp.review.base-url", () -> baseUrl);
    }

    @Autowired
    protected WebTestClient client;
    @Autowired
    protected DependencyGuard guard;
    @Autowired
    protected MeterRegistry meters;

    /** Port của các context đã làm nóng; context được cache giữa các class cùng cấu hình. */
    private static final Set<Integer> WARMED_CONTEXTS = ConcurrentHashMap.newKeySet();

    @LocalServerPort
    private int port;

    /**
     * Request đầu tiên của một context mới phải nạp class, JIT và mở kết nối, nên có thể
     * vượt timeout 400 ms của Product. Làm nóng một lần để test đo hành vi chứ không đo
     * cold start. Circuit breaker sống cùng context, nên phải reset giữa các test.
     */
    @BeforeEach
    void resetUpstreams() {
        if (WARMED_CONTEXTS.add(port)) {
            stubHealthyUpstreams();
            for (int i = 0; i < 3; i++) {
                getPdp("smart-watch").expectBody().returnResult();
            }
        }
        UPSTREAMS.resetAll();
        for (Dependency dependency : Dependency.values()) {
            guard.breaker(dependency).reset();
        }
    }

    // ---------------------------------------------------------------- stubs

    protected void stubProduct(Object body) {
        stubProduct(json(body));
    }

    protected void stubProduct(ResponseDefinitionBuilder response) {
        UPSTREAMS.stubFor(get(urlEqualTo(PRODUCT_PATH)).willReturn(response));
    }

    protected void stubInventory(Object body) {
        stubInventory(json(body));
    }

    protected void stubInventory(ResponseDefinitionBuilder response) {
        UPSTREAMS.stubFor(post(urlEqualTo(INVENTORY_PATH)).willReturn(response));
    }

    protected void stubReview(Object body) {
        stubReview(json(body));
    }

    protected void stubReview(ResponseDefinitionBuilder response) {
        MappingBuilder mapping = get(urlPathEqualTo(REVIEW_PATH));
        UPSTREAMS.stubFor(mapping.willReturn(response));
    }

    /** Product, Inventory (đen 42 còn, trắng 42 hết, đen 46 còn) và Review đều khỏe. */
    protected void stubHealthyUpstreams() {
        stubProduct(Fixtures.product());
        stubInventory(Fixtures.inventory(java.util.List.of(
                Fixtures.availability(Fixtures.SKU_BLACK_42, "IN_STOCK"),
                Fixtures.availability(Fixtures.SKU_WHITE_42, "OUT_OF_STOCK"),
                Fixtures.availability(Fixtures.SKU_BLACK_46, "IN_STOCK"))));
        stubReview(Fixtures.review());
    }

    protected static ResponseDefinitionBuilder json(Object body) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(Fixtures.json(body));
    }

    protected static ResponseDefinitionBuilder status(int status) {
        return aResponse().withStatus(status)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"code\":\"STUB\"}");
    }

    protected WebTestClient.ResponseSpec getPdp(String slug) {
        return client.get().uri("/api/v1/storefront/products/{slug}", slug).exchange();
    }

    /** Body JSON của response đã qua các assertion status/header. */
    protected static JsonNode bodyOf(WebTestClient.ResponseSpec response) {
        try {
            return READER.readTree(response.expectBody().returnResult().getResponseBody());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static List<String> availabilityStatuses(JsonNode body) {
        return body.path("skus").findValuesAsText("availability_status");
    }

    // ---------------------------------------------------------------- metrics

    protected double counter(String name, String tagKey, String tagValue) {
        Counter counter = meters.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }

    protected long calls(Dependency dependency, String outcome) {
        Timer timer = meters.find("pdp.dependency.calls")
                .tag("dependency", dependency.tag()).tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }
}
