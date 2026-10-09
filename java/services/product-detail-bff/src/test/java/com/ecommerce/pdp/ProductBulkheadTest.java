package com.ecommerce.pdp;

import com.ecommerce.pdp.resilience.Dependency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bulkhead Product đầy là 503 không {@code Retry-After} và không tính vào circuit
 * (operations.md §Health và degradation).
 */
@TestPropertySource(properties = "pdp.product.bulkhead=1")
class ProductBulkheadTest extends AbstractPdpTest {

    @Test
    @DisplayName("Bulkhead Product đầy: request vượt giới hạn nhận 503 no-store, không Retry-After, không tính circuit")
    void fullProductBulkheadIsUnavailableWithoutTouchingTheBreaker() {
        stubProduct(json(Fixtures.product()).withFixedDelay(200));
        stubInventory(Fixtures.inventory(List.of(Fixtures.availability(Fixtures.SKU_BLACK_42, "IN_STOCK"))));
        stubReview(Fixtures.review());
        long rejectedBefore = calls(Dependency.PRODUCT, "bulkhead_rejected");

        List<Integer> statuses = Flux.range(0, 2)
                .flatMap(i -> Mono.fromCallable(() -> {
                    var result = getPdp("smart-watch").expectBody().returnResult();
                    if (result.getStatus().value() == 503) {
                        assertThat(result.getResponseHeaders().getCacheControl()).isEqualTo("no-store");
                        assertThat(result.getResponseHeaders().containsKey("Retry-After")).isFalse();
                    }
                    return result.getStatus().value();
                }).subscribeOn(Schedulers.boundedElastic()))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(statuses).containsExactlyInAnyOrder(200, 503);
        assertThat(calls(Dependency.PRODUCT, "bulkhead_rejected") - rejectedBefore).isEqualTo(1);
        assertThat(guard.breaker(Dependency.PRODUCT).getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(guard.breaker(Dependency.PRODUCT).getMetrics().getNumberOfFailedCalls()).isZero();
    }
}
