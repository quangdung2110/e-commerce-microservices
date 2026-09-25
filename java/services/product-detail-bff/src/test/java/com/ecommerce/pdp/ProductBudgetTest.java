package com.ecommerce.pdp;

import com.ecommerce.pdp.resilience.Dependency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deadline còn dưới 100 ms trước pha Product thì trả {@code 503} mà không gọi Product
 * (ADR-007). Deadline 90 ms mô phỏng request đã tiêu gần hết budget trước khi tới BFF.
 */
@TestPropertySource(properties = "pdp.deadline=90ms")
class ProductBudgetTest extends AbstractPdpTest {

    @Test
    @DisplayName("Không đủ budget cho Product: 503 no-store, không Retry-After, không tính circuit")
    void exhaustedBudgetBeforeProductIsUnavailable() {
        stubHealthyUpstreams();
        long skipped = calls(Dependency.PRODUCT, "budget_skipped");

        getPdp("smart-watch").expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().doesNotExist("Retry-After");

        UPSTREAMS.verify(0, anyRequestedFor(anyUrl()));
        assertThat(calls(Dependency.PRODUCT, "budget_skipped") - skipped).isEqualTo(1);
        assertThat(guard.breaker(Dependency.PRODUCT).getMetrics().getNumberOfBufferedCalls()).isZero();
    }
}
