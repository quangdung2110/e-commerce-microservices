package com.ecommerce.checkout;

import com.ecommerce.checkout.resilience.Dependency;
import com.ecommerce.checkout.resilience.DependencyGuard;
import com.ecommerce.checkout.support.FakeProduct;
import com.ecommerce.checkout.support.Fixtures;
import com.ecommerce.checkout.support.MutableClock;
import com.ecommerce.checkout.support.RecordedSpans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Checkout running for real: a Redis container (Lua scripts execute for real), a WireMock server for the
 * five HTTP providers, an in-process gRPC server for Product, and a clock tests can move. Every test uses
 * its own user, so tests share Redis without sharing an active pointer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@org.springframework.boot.autoconfigure.ImportAutoConfiguration(exclude =
        org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration.class)
@Import(AbstractCheckoutTest.TestClockConfig.class)
@TestPropertySource(properties = {
        "checkout.keys.token-hmac-keys=" + AbstractCheckoutTest.HMAC_KEYS,
        "checkout.keys.token-hmac-active-version=v1",
        "checkout.keys.encryption-keys=" + AbstractCheckoutTest.ENCRYPTION_KEYS,
        "checkout.keys.encryption-active-version=v1",
        "checkout.media.cdn-base-url=https://cdn.test/media",
        "checkout.media.placeholder-url=https://cdn.test/placeholder.png",
        "checkout.voucher-options-enabled=true",
        // Short enough to test an Order timeout; still below the mesh timeout (15 s).
        "checkout.timeouts.order-post=700ms",
        "management.tracing.sampling.probability=1.0"
})
public abstract class AbstractCheckoutTest {

    // 32 bytes of 'h' and of 'k'; annotation values must be compile-time constants.
    protected static final String HMAC_KEYS = "v1:aGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGg=";
    protected static final String ENCRYPTION_KEYS = "v1:a2tra2tra2tra2tra2tra2tra2tra2tra2tra2tra2s=";

    protected static final String CART_PATH = "/internal/v1/carts/.+/checkout-snapshot";
    protected static final String USER_PATH = "/internal/v1/users/.+/addresses/.+/checkout-snapshot";
    protected static final String INVENTORY_PATH = "/internal/inventory/v1/allocation-previews";
    protected static final String PROMOTION_CALC_PATH = "/internal/v1/promotion-discount-calculations";
    protected static final String PROMOTION_OPTIONS_PATH = "/internal/v1/promotion-voucher-options";
    protected static final String ORDER_PATH = "/internal/v1/checkout-order-submissions";

    protected static final WireMockServer UPSTREAMS = new WireMockServer(options().dynamicPort().globalTemplating(true));
    protected static final FakeProduct PRODUCT = new FakeProduct();
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    protected static final ObjectMapper JSON = new ObjectMapper();

    static {
        UPSTREAMS.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        String http = "http://localhost:" + UPSTREAMS.port();
        registry.add("checkout.redis-url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("checkout.endpoints.cart-base-url", () -> http);
        registry.add("checkout.endpoints.user-base-url", () -> http);
        registry.add("checkout.endpoints.inventory-base-url", () -> http);
        registry.add("checkout.endpoints.promotion-base-url", () -> http);
        registry.add("checkout.endpoints.order-base-url", () -> http);
        registry.add("checkout.endpoints.product-grpc-target", () -> "localhost:" + PRODUCT.port());
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        /** Boot adds every SpanProcessor bean to the tracer provider; this one keeps the spans for assertions. */
        @Bean
        RecordedSpans recordedSpans() {
            return new RecordedSpans();
        }
    }

    @Autowired
    protected WebTestClient client;
    @Autowired
    protected DependencyGuard guard;
    @Autowired
    protected MeterRegistry meters;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected ReactiveStringRedisTemplate redis;
    @Autowired
    protected RecordedSpans spans;

    /** Ports of the contexts already warmed up; contexts are cached across test classes. */
    private static final java.util.Set<Integer> WARMED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @org.springframework.boot.test.web.server.LocalServerPort
    private int serverPort;

    @BeforeEach
    void resetWorld() {
        UPSTREAMS.resetAll();
        PRODUCT.reset();
        clock.reset();
        for (Dependency dependency : Dependency.values()) {
            guard.breaker(dependency).reset();
        }
        client = client.mutate().responseTimeout(Duration.ofSeconds(40)).build();
        stubHealthy();
        if (WARMED.add(serverPort)) {
            // The first requests of a fresh JVM load classes and open connections and can exceed the 500 ms
            // Cart budget; warm up once so the tests measure behaviour, not a cold start.
            for (int i = 0; i < 3; i++) {
                String user = newUser();
                preview(user).returnResult(String.class);
            }
            resetWorld();
        }
    }

    // ------------------------------------------------------------------ stubs

    /** Every provider healthy, the default cart and a PAYMENT_READY Order. */
    protected void stubHealthy() {
        stubCart(Fixtures.cart());
        stubAddress();
        PRODUCT.put(Fixtures.sku1().build());
        PRODUCT.put(Fixtures.sku2().build());
        stubInventory(Fixtures.allocatable());
        stubPromotionCalculation(Fixtures.eligibleCalculation());
        stubOrderPost(201);
        stubOrderGet("PAYMENT_READY");
    }

    protected void stubCart(Object body) {
        UPSTREAMS.stubFor(get(urlPathMatching(CART_PATH)).willReturn(json(200, body)));
    }

    protected void stubCart(ResponseDefinitionBuilder response) {
        UPSTREAMS.stubFor(get(urlPathMatching(CART_PATH)).willReturn(response));
    }

    protected void stubAddress() {
        UPSTREAMS.stubFor(get(urlPathMatching(USER_PATH)).willReturn(json(200, Fixtures.address())));
    }

    protected void stubInventory(Object body) {
        UPSTREAMS.stubFor(post(urlEqualTo(INVENTORY_PATH)).willReturn(json(200, body)));
    }

    protected void stubPromotionCalculation(Object body) {
        UPSTREAMS.stubFor(post(urlEqualTo(PROMOTION_CALC_PATH)).willReturn(json(200, body)));
    }

    protected void stubPromotionOptions(Object body) {
        UPSTREAMS.stubFor(post(urlEqualTo(PROMOTION_OPTIONS_PATH)).willReturn(json(200, body)));
    }

    /** Order accepts and answers PAYMENT_READY for whatever token the request carries. */
    protected void stubOrderPost(int status) {
        UPSTREAMS.stubFor(post(urlEqualTo(ORDER_PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"checkout_token\":\"{{jsonPath request.body '$.checkout_token'}}\",\"order_id\":\""
                        + Fixtures.ORDER_ID + "\",\"order_status\":\"PENDING_PAYMENT\",\"payment\":"
                        + Fixtures.json(Fixtures.payment(true, "secret-from-post")) + "}")));
    }

    protected void stubOrderPost(ResponseDefinitionBuilder response) {
        UPSTREAMS.stubFor(post(urlEqualTo(ORDER_PATH)).willReturn(response));
    }

    /** Recovery read for any token; {@code PAYMENT_READY} carries a fresh secret. */
    protected void stubOrderGet(String state) {
        String body = switch (state) {
            case "PAYMENT_READY" -> "{\"checkout_token\":\"{{request.pathSegments.[3]}}\",\"state\":\"PAYMENT_READY\","
                    + "\"order_id\":\"" + Fixtures.ORDER_ID + "\",\"payment\":"
                    + Fixtures.json(Fixtures.payment(true, "secret-from-get")) + ",\"failure\":null,"
                    + "\"updated_at\":\"2026-10-07T00:00:00Z\"}";
            case "PREPARING" -> "{\"checkout_token\":\"{{request.pathSegments.[3]}}\",\"state\":\"PREPARING\","
                    + "\"order_id\":null,\"payment\":null,\"failure\":null,\"updated_at\":\"2026-10-07T00:00:00Z\"}";
            default -> throw new IllegalArgumentException(state);
        };
        UPSTREAMS.stubFor(get(urlPathMatching(ORDER_PATH + "/.+")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    /** Recovery read answering FAILED or RECALCULATION_REQUIRED with Order's own problem. */
    protected void stubOrderGetFailure(String state, Map<String, Object> failure) {
        String body = "{\"checkout_token\":\"{{request.pathSegments.[3]}}\",\"state\":\"" + state
                + "\",\"order_id\":null,\"payment\":null,\"failure\":" + Fixtures.json(failure)
                + ",\"updated_at\":\"2026-10-07T00:00:00Z\"}";
        UPSTREAMS.stubFor(get(urlPathMatching(ORDER_PATH + "/.+")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    protected void stubOrderGet(ResponseDefinitionBuilder response) {
        UPSTREAMS.stubFor(get(urlPathMatching(ORDER_PATH + "/.+")).willReturn(response));
    }

    protected static ResponseDefinitionBuilder json(int status, Object body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody(Fixtures.json(body));
    }

    protected static ResponseDefinitionBuilder problem(int status, String code) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/problem+json")
                .withBody(Fixtures.json(Fixtures.problem(status, code)));
    }

    // ------------------------------------------------------------------ requests

    protected static String newUser() {
        return UUID.randomUUID().toString();
    }

    protected WebTestClient.ResponseSpec preview(String user, String idempotencyKey, Map<String, Object> body) {
        WebTestClient.RequestBodySpec request = client.post().uri("/api/v1/checkout/preview")
                .contentType(MediaType.APPLICATION_JSON);
        if (user != null) {
            request.header("X-User-Id", user);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.bodyValue(Fixtures.json(body)).exchange();
    }

    protected static Map<String, Object> previewBody(String previous, String promotionId) {
        return Fixtures.map("previous_checkout_token", previous, "delivery_address_id", Fixtures.ADDRESS_ID,
                "promotion_id", promotionId);
    }

    protected WebTestClient.ResponseSpec preview(String user) {
        return preview(user, UUID.randomUUID().toString(), previewBody(null, null));
    }

    /** A successful fresh Preview; returns the response body. */
    protected JsonNode previewOk(String user) {
        return bodyOf(preview(user).expectStatus().isOk());
    }

    protected JsonNode previewOk(String user, String previous, String promotionId) {
        return bodyOf(preview(user, UUID.randomUUID().toString(), previewBody(previous, promotionId))
                .expectStatus().isOk());
    }

    protected WebTestClient.ResponseSpec submit(String user, String token) {
        return client.post().uri("/api/v1/checkout/submit").header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Fixtures.json(Fixtures.map("checkout_token", token))).exchange();
    }

    protected WebTestClient.ResponseSpec status(String user, String token) {
        return client.get().uri("/api/v1/checkout/submissions/{token}", token).header("X-User-Id", user).exchange();
    }

    protected WebTestClient.ResponseSpec voucherOptions(String user, String token) {
        return client.get().uri("/api/v1/checkout/previews/{token}/voucher-options", token)
                .header("X-User-Id", user).exchange();
    }

    protected static JsonNode bodyOf(WebTestClient.ResponseSpec response) {
        try {
            return JSON.readTree(response.expectBody().returnResult().getResponseBody());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ verification

    protected static int requests(RequestPatternBuilder pattern) {
        return UPSTREAMS.countRequestsMatching(pattern.build()).getCount();
    }

    protected static int orderPosts() {
        return requests(postRequestedFor(urlEqualTo(ORDER_PATH)));
    }

    protected static int orderGets() {
        return requests(getRequestedFor(urlPathMatching(ORDER_PATH + "/.+")));
    }

    protected static List<LoggedRequest> posted(String path) {
        return UPSTREAMS.findAll(postRequestedFor(urlEqualTo(path)));
    }

    protected static JsonNode requestBody(LoggedRequest request) {
        try {
            return JSON.readTree(request.getBodyAsString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static int totalRequests() {
        return UPSTREAMS.getAllServeEvents().size();
    }

    protected double counter(String name, String tagKey, String tagValue) {
        Counter counter = meters.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }

    protected long calls(Dependency dependency, String outcome) {
        Timer timer = meters.find("checkout.dependency.calls")
                .tag("dependency", dependency.tag()).tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    /** Names of every Redis key of this test run that contain {@code fragment}. */
    protected List<String> redisKeys(String fragment) {
        return redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match("*" + fragment + "*").count(1000).build())
                .collectList().block(Duration.ofSeconds(5));
    }
}
