package com.ecommerce.pdp;

import com.ecommerce.pdp.client.RequestContext;
import com.ecommerce.pdp.compose.ProductDetailComposer;
import com.ecommerce.pdp.resilience.Deadline;
import com.ecommerce.pdp.resilience.Dependency;
import com.ecommerce.pdp.resilience.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Arrays;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client hoặc gateway hủy request thì mọi call con đang chạy bị hủy (ADR-004 §2). Lời gọi
 * bị hủy không có kết quả nào — không timeout, không lỗi — nên không có outcome nào được
 * ghi và circuit breaker không đếm nó.
 */
class CancellationTest extends AbstractPdpTest {

    @Autowired
    private ProductDetailComposer composer;

    @Test
    @DisplayName("Hủy giữa pha 2 hủy cả Inventory lẫn Review, không ghi outcome hay lỗi circuit")
    void cancellingThePdpCancelsInFlightCalls() throws InterruptedException {
        stubProduct(Fixtures.product());
        stubInventory(json(Fixtures.inventory(java.util.List.of())).withFixedDelay(2_000));
        stubReview(json(Fixtures.review()).withFixedDelay(2_000));
        long reviewBefore = totalCalls(Dependency.REVIEW);
        long inventoryBefore = totalCalls(Dependency.INVENTORY);
        RequestContext context = new RequestContext("req-cancel-1", "0af7651916cd43dd8448eb211c80319c",
                Deadline.startingNow(Duration.ofSeconds(1)));

        StepVerifier.create(composer.compose("smart-watch", context))
                .expectSubscription()
                .then(this::awaitPhaseTwoInFlight)
                .thenCancel()
                .verify(Duration.ofSeconds(2));
        // Quá cả timeout 300 ms của pha 2: nếu call chưa bị hủy thì timeout đã được ghi.
        Thread.sleep(500);

        UPSTREAMS.verify(1, postRequestedFor(urlEqualTo(INVENTORY_PATH)));
        UPSTREAMS.verify(1, getRequestedFor(urlPathEqualTo(REVIEW_PATH)));
        assertThat(totalCalls(Dependency.REVIEW)).isEqualTo(reviewBefore);
        assertThat(totalCalls(Dependency.INVENTORY)).isEqualTo(inventoryBefore);
        assertThat(guard.breaker(Dependency.REVIEW).getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(guard.breaker(Dependency.INVENTORY).getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    /** Hủy đúng lúc cả hai call pha 2 đã tới provider và đang chờ response. */
    private void awaitPhaseTwoInFlight() {
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < giveUpAt) {
            boolean inventorySent = !UPSTREAMS.findAll(postRequestedFor(urlEqualTo(INVENTORY_PATH))).isEmpty();
            boolean reviewSent = !UPSTREAMS.findAll(getRequestedFor(urlPathEqualTo(REVIEW_PATH))).isEmpty();
            if (inventorySent && reviewSent) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("phase 2 calls never reached the providers");
    }

    private long totalCalls(Dependency dependency) {
        return Arrays.stream(Outcome.values()).mapToLong(outcome -> calls(dependency, outcome.tag())).sum();
    }
}
