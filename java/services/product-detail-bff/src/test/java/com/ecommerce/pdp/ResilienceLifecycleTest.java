package com.ecommerce.pdp;

import com.ecommerce.pdp.resilience.Dependency;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vòng đời circuit breaker và bulkhead theo từng dependency (ADR-004 §3). Ngưỡng được hạ
 * để test chạy nhanh; cơ chế giống production.
 */
@TestPropertySource(properties = {
        "pdp.circuit-breaker.minimum-calls=4",
        "pdp.circuit-breaker.open-duration=300ms",
        "pdp.circuit-breaker.half-open-calls=2",
        "pdp.review.bulkhead=1"
})
class ResilienceLifecycleTest extends AbstractPdpTest {

    @Test
    @DisplayName("Circuit Review mở, half-open rồi đóng mà không ảnh hưởng Product và Inventory")
    void reviewCircuitOpensHalfOpensAndClosesInIsolation() throws InterruptedException {
        stubProduct(Fixtures.product());
        stubInventory(Fixtures.inventory(List.of(Fixtures.availability(Fixtures.SKU_BLACK_42, "IN_STOCK"))));
        stubReview(status(500));

        for (int i = 0; i < 4; i++) {
            getPdp("smart-watch").expectStatus().isOk();
        }
        assertThat(guard.breaker(Dependency.REVIEW).getState()).isEqualTo(CircuitBreaker.State.OPEN);

        getPdp("smart-watch").expectStatus().isOk()
                .expectBody().jsonPath("$.review_section.status").isEqualTo("UNAVAILABLE");
        UPSTREAMS.verify(4, getRequestedFor(urlPathEqualTo(REVIEW_PATH)));
        UPSTREAMS.verify(5, getRequestedFor(urlEqualTo(PRODUCT_PATH)));
        UPSTREAMS.verify(5, postRequestedFor(urlEqualTo(INVENTORY_PATH)));
        assertThat(guard.breaker(Dependency.PRODUCT).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(guard.breaker(Dependency.INVENTORY).getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        stubReview(Fixtures.review());
        Thread.sleep(350);
        getPdp("smart-watch").expectStatus().isOk()
                .expectBody().jsonPath("$.review_section.status").isEqualTo("AVAILABLE");
        assertThat(guard.breaker(Dependency.REVIEW).getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        getPdp("smart-watch").expectStatus().isOk();
        assertThat(guard.breaker(Dependency.REVIEW).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Bulkhead đầy từ chối ngay, trả UNAVAILABLE và không tính vào circuit")
    void fullBulkheadRejectsWithoutTouchingTheBreaker() {
        stubProduct(Fixtures.product());
        stubInventory(Fixtures.inventory(List.of(Fixtures.availability(Fixtures.SKU_BLACK_42, "IN_STOCK"))));
        stubReview(json(Fixtures.review()).withFixedDelay(200));
        long rejectedBefore = calls(Dependency.REVIEW, "bulkhead_rejected");

        List<String> statuses = Flux.range(0, 2)
                .flatMap(i -> Mono.fromCallable(() -> reviewStatus()).subscribeOn(
                        reactor.core.scheduler.Schedulers.boundedElastic()))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(statuses).containsExactlyInAnyOrder("AVAILABLE", "UNAVAILABLE");
        assertThat(calls(Dependency.REVIEW, "bulkhead_rejected") - rejectedBefore).isEqualTo(1);
        assertThat(guard.breaker(Dependency.REVIEW).getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(guard.breaker(Dependency.REVIEW).getMetrics().getNumberOfFailedCalls()).isZero();
    }

    private String reviewStatus() {
        EntityExchangeResult<byte[]> result = getPdp("smart-watch").expectStatus().isOk().expectBody().returnResult();
        String body = new String(result.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8);
        return body.contains("\"status\":\"UNAVAILABLE\"") ? "UNAVAILABLE" : "AVAILABLE";
    }
}
