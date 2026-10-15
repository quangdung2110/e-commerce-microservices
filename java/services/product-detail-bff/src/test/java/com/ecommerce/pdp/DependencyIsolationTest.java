package com.ecommerce.pdp;

import com.ecommerce.pdp.resilience.Dependency;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Circuit breaker và bulkhead của Product và Inventory (Review ở {@link ResilienceLifecycleTest}):
 * mỗi dependency mở, half-open, đóng và từ chối độc lập (ADR-004 §3). Ngưỡng được hạ để
 * test chạy nhanh; cơ chế giống production.
 */
@TestPropertySource(properties = {
        "pdp.circuit-breaker.minimum-calls=4",
        "pdp.circuit-breaker.open-duration=300ms",
        "pdp.circuit-breaker.half-open-calls=2",
        "pdp.inventory.bulkhead=1"
})
class DependencyIsolationTest extends AbstractPdpTest {

    @Test
    @DisplayName("Circuit Inventory mở, half-open rồi đóng; PDP vẫn 200 và Product/Review không bị ảnh hưởng")
    void inventoryCircuitOpensHalfOpensAndClosesInIsolation() throws InterruptedException {
        stubProduct(Fixtures.product());
        stubInventory(status(503));
        stubReview(Fixtures.review());

        for (int i = 0; i < 4; i++) {
            getPdp("smart-watch").expectStatus().isOk();
        }
        assertThat(guard.breaker(Dependency.INVENTORY).getState()).isEqualTo(CircuitBreaker.State.OPEN);

        getPdp("smart-watch").expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store");
        UPSTREAMS.verify(4, postRequestedFor(urlEqualTo(INVENTORY_PATH)));
        UPSTREAMS.verify(5, getRequestedFor(urlEqualTo(PRODUCT_PATH)));
        UPSTREAMS.verify(5, getRequestedFor(urlPathEqualTo(REVIEW_PATH)));
        assertThat(guard.breaker(Dependency.PRODUCT).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(guard.breaker(Dependency.REVIEW).getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        stubInventory(Fixtures.inventory(List.of(
                Fixtures.availability(Fixtures.SKU_BLACK_42, "IN_STOCK"),
                Fixtures.availability(Fixtures.SKU_WHITE_42, "IN_STOCK"),
                Fixtures.availability(Fixtures.SKU_BLACK_46, "IN_STOCK"))));
        Thread.sleep(350);
        assertThat(availabilityStatuses(bodyOf(getPdp("smart-watch").expectStatus().isOk())))
                .containsOnly("IN_STOCK");
        assertThat(guard.breaker(Dependency.INVENTORY).getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        getPdp("smart-watch").expectStatus().isOk();
        assertThat(guard.breaker(Dependency.INVENTORY).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Circuit Product mở trả 503 kèm Retry-After, half-open rồi đóng khi Product phục hồi")
    void productCircuitOpensHalfOpensAndCloses() throws InterruptedException {
        stubHealthyUpstreams();
        stubProduct(status(500));

        for (int i = 0; i < 4; i++) {
            // Before the circuit opens a 503 has no estimate of its own: the default wait of contracts ADR-005.
            getPdp("smart-watch").expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectHeader().valueEquals("Retry-After", "2");
        }
        assertThat(guard.breaker(Dependency.PRODUCT).getState()).isEqualTo(CircuitBreaker.State.OPEN);

        getPdp("smart-watch").expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists("Retry-After");
        UPSTREAMS.verify(4, getRequestedFor(urlEqualTo(PRODUCT_PATH)));

        stubProduct(Fixtures.product());
        Thread.sleep(350);
        getPdp("smart-watch").expectStatus().isOk();
        assertThat(guard.breaker(Dependency.PRODUCT).getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        getPdp("smart-watch").expectStatus().isOk();
        assertThat(guard.breaker(Dependency.PRODUCT).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Bulkhead Inventory đầy từ chối ngay: SKU UNKNOWN, không tính vào circuit")
    void fullInventoryBulkheadRejectsWithoutTouchingTheBreaker() {
        stubProduct(Fixtures.product());
        stubInventory(json(Fixtures.inventory(List.of(Fixtures.availability(Fixtures.SKU_BLACK_42, "IN_STOCK"))))
                .withFixedDelay(200));
        stubReview(Fixtures.review());
        long rejectedBefore = calls(Dependency.INVENTORY, "bulkhead_rejected");

        List<Integer> unknownSkus = Flux.range(0, 2)
                .flatMap(i -> Mono.fromCallable(() -> availabilityStatuses(bodyOf(getPdp("smart-watch")
                                .expectStatus().isOk())).stream().filter("UNKNOWN"::equals).count())
                        .map(Long::intValue)
                        .subscribeOn(Schedulers.boundedElastic()))
                .collectList()
                .block(Duration.ofSeconds(5));

        // Một request được gọi Inventory (2 SKU không có item nên UNKNOWN), request kia bị từ chối (3 SKU).
        assertThat(unknownSkus).containsExactlyInAnyOrder(2, 3);
        assertThat(calls(Dependency.INVENTORY, "bulkhead_rejected") - rejectedBefore).isEqualTo(1);
        assertThat(guard.breaker(Dependency.INVENTORY).getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(guard.breaker(Dependency.INVENTORY).getMetrics().getNumberOfFailedCalls()).isZero();
    }
}
