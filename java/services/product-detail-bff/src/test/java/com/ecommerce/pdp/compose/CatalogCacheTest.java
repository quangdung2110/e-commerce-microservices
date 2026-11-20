package com.ecommerce.pdp.compose;

import com.ecommerce.pdp.AbstractPdpTest;
import com.ecommerce.pdp.Fixtures;
import com.ecommerce.pdp.client.ProductPayload;
import com.ecommerce.pdp.client.RequestContext;
import com.ecommerce.pdp.resilience.Deadline;
import com.ecommerce.pdp.resilience.Dependency;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catalog cache của PDP (ADR-011): chỉ catalog Product được giữ trong TTL; {@code 404} và
 * lỗi không được giữ; request trùng slug chờ chung một lời gọi Product; Inventory và Review
 * vẫn được gọi theo từng request.
 */
@TestPropertySource(properties = {"pdp.catalog-cache.enabled=true", "pdp.catalog-cache.ttl=1s"})
class CatalogCacheTest extends AbstractPdpTest {

    @Autowired
    private CatalogCache cache;
    @Autowired
    private ProductDetailComposer composer;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void emptyCache() {
        cache.invalidateAll();
    }

    @Test
    void coldCatalogLoadPreservesTheInitiatingTrace() {
        stubHealthyUpstreams();
        String traceId = "1234567890abcdef1234567890abcdef";
        client.get().uri("/api/v1/storefront/products/smart-watch")
                .header("X-Request-Id", "cache-trace-test-0001")
                .header("traceparent", "00-" + traceId + "-1234567890abcdef-01")
                .exchange().expectStatus().isOk().expectBody().returnResult();

        var sameTrace = com.github.tomakehurst.wiremock.client.WireMock.matching(
                "00-" + traceId + "-[0-9a-f]{16}-01");
        UPSTREAMS.verify(getRequestedFor(urlEqualTo(PRODUCT_PATH)).withHeader("traceparent", sameTrace));
        UPSTREAMS.verify(postRequestedFor(urlEqualTo(INVENTORY_PATH)).withHeader("traceparent", sameTrace));
        UPSTREAMS.verify(getRequestedFor(urlPathEqualTo(REVIEW_PATH)).withHeader("traceparent", sameTrace));
    }

    @Test
    @DisplayName("Lượt xem thứ hai trong TTL không gọi Product, nhưng vẫn gọi Inventory và Review")
    void secondViewWithinTtlIsServedFromCache() {
        stubHealthyUpstreams();
        double hitsBefore = counter("pdp.catalog.cache.requests", "result", "hit");

        JsonNode first = bodyOf(getPdp("smart-watch").expectStatus().isOk());
        JsonNode second = bodyOf(getPdp("smart-watch").expectStatus().isOk());

        assertThat(second).isEqualTo(first);
        UPSTREAMS.verify(1, getRequestedFor(urlEqualTo(PRODUCT_PATH)));
        UPSTREAMS.verify(2, postRequestedFor(urlEqualTo(INVENTORY_PATH)));
        UPSTREAMS.verify(2, getRequestedFor(urlPathEqualTo(REVIEW_PATH)));
        assertThat(counter("pdp.catalog.cache.requests", "result", "hit") - hitsBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("Hết TTL thì gọi Product lại: catalog trên PDP trễ tối đa một TTL")
    void entryExpiresAfterTtl() throws InterruptedException {
        stubHealthyUpstreams();
        getPdp("smart-watch").expectStatus().isOk();

        Thread.sleep(1_200);
        getPdp("smart-watch").expectStatus().isOk();

        UPSTREAMS.verify(2, getRequestedFor(urlEqualTo(PRODUCT_PATH)));
    }

    @Test
    @DisplayName("404 không được giữ: sản phẩm vừa được mở bán hiện ngay ở lượt sau")
    void notFoundIsNeverCached() {
        stubProduct(status(404));
        getPdp("smart-watch").expectStatus().isNotFound();

        stubHealthyUpstreams();
        getPdp("smart-watch").expectStatus().isOk();

        UPSTREAMS.verify(2, getRequestedFor(urlEqualTo(PRODUCT_PATH)));
    }

    @Test
    @DisplayName("Lỗi Product không được giữ: lượt sau gọi lại và hồi phục ngay")
    void failureIsNeverCached() {
        stubProduct(status(503));
        getPdp("smart-watch").expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        stubHealthyUpstreams();
        getPdp("smart-watch").expectStatus().isOk();

        UPSTREAMS.verify(2, getRequestedFor(urlEqualTo(PRODUCT_PATH)));
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Tồn kho không bao giờ lấy từ cache: Inventory lỗi rồi hồi phục thì lượt sau có trạng thái mới")
    void availabilityIsAlwaysFresh() {
        stubHealthyUpstreams();
        stubInventory(status(500));
        JsonNode degraded = bodyOf(getPdp("smart-watch").expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store"));
        assertThat(availabilityStatuses(degraded)).containsOnly("UNKNOWN");

        stubHealthyUpstreams();
        JsonNode recovered = bodyOf(getPdp("smart-watch").expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", FULL_CACHE_CONTROL));

        assertThat(availabilityStatuses(recovered)).hasSize(3).doesNotContain("UNKNOWN")
                .contains("IN_STOCK", "OUT_OF_STOCK");
        UPSTREAMS.verify(1, getRequestedFor(urlEqualTo(PRODUCT_PATH)));
    }

    @Test
    @DisplayName("Nhiều request cùng slug khi chưa có entry chỉ tạo một lời gọi Product")
    void concurrentMissesShareOneProductCall() {
        stubHealthyUpstreams();
        stubProduct(json(Fixtures.product()).withFixedDelay(200));
        double coalescedBefore = counter("pdp.catalog.cache.requests", "result", "coalesced");
        double hitsBefore = counter("pdp.catalog.cache.requests", "result", "hit");

        // WebTestClient chặn tới khi có response: mỗi request chạy trên một thread riêng để
        // cả 20 cùng tới BFF trong lúc lời gọi Product đầu tiên còn chờ 200 ms.
        List<Integer> statuses = Flux.range(0, 20)
                .flatMap(i -> Mono.fromCallable(() -> client.get().uri("/api/v1/storefront/products/smart-watch")
                                .exchange().returnResult(String.class).getStatus().value())
                        .subscribeOn(Schedulers.boundedElastic()), 20)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(statuses).hasSize(20).containsOnly(200);
        UPSTREAMS.verify(1, getRequestedFor(urlEqualTo(PRODUCT_PATH)));
        double joined = counter("pdp.catalog.cache.requests", "result", "coalesced") - coalescedBefore;
        double hits = counter("pdp.catalog.cache.requests", "result", "hit") - hitsBefore;
        // Scheduling can deliver some requests after the 200ms load finishes. They must
        // reuse that result too; the single Product call above is the single-flight guarantee.
        assertThat(joined).isPositive();
        assertThat(joined + hits).isEqualTo(19);
        assertThat(cache.inFlight()).isZero();
    }

    @Test
    @DisplayName("Request khởi tạo bị hủy thì request đang chờ chung vẫn nhận catalog")
    void cancellingTheInitiatorDoesNotFailWaiters() {
        stubHealthyUpstreams();
        stubProduct(json(Fixtures.product()).withFixedDelay(250));
        RequestContext initiator = context("req-cache-initiator");
        RequestContext waiter = context("req-cache-waiter");

        Disposable initiatorRequest = composer.compose("smart-watch", initiator).subscribe();
        awaitProductRequested();
        initiatorRequest.dispose();

        // Lời gọi Product chung vẫn đang chờ 250 ms: request sau chờ chung và nhận kết quả.
        StepVerifier.create(composer.compose("smart-watch", waiter))
                .assertNext(result -> assertThat(result).isInstanceOf(PdpResult.Found.class))
                .expectComplete()
                .verify(Duration.ofSeconds(3));

        UPSTREAMS.verify(1, getRequestedFor(urlEqualTo(PRODUCT_PATH)));
        assertThat(cache.size()).isEqualTo(1);
        assertThat(calls(Dependency.PRODUCT, "success")).isPositive();
    }

    @Test
    @DisplayName("Ước lượng bộ nhớ tăng theo kích thước payload")
    void weightGrowsWithPayload() {
        Map<String, Object> small = Fixtures.product();
        Map<String, Object> large = Fixtures.product();
        large.put("description", "x".repeat(10_000));

        int smallBytes = CatalogCache.estimatedBytes(objectMapper.convertValue(small, ProductPayload.class));
        int largeBytes = CatalogCache.estimatedBytes(objectMapper.convertValue(large, ProductPayload.class));

        assertThat(smallBytes).isPositive();
        assertThat(largeBytes - smallBytes).isGreaterThanOrEqualTo(20_000);
    }

    private void awaitProductRequested() {
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (UPSTREAMS.findAll(getRequestedFor(urlEqualTo(PRODUCT_PATH))).isEmpty()) {
            if (System.nanoTime() > giveUpAt) {
                throw new AssertionError("the Product call never reached the provider");
            }
            Thread.onSpinWait();
        }
    }

    private static RequestContext context(String requestId) {
        return new RequestContext(requestId, "0af7651916cd43dd8448eb211c80319c",
                Deadline.startingNow(Duration.ofSeconds(1)));
    }
}
