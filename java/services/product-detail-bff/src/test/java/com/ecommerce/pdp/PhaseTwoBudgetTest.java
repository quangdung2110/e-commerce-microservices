package com.ecommerce.pdp;

import com.ecommerce.pdp.resilience.Dependency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deadline còn dưới 100 ms sau pha Product thì bỏ pha 2 (ADR-004, ADR-007).
 *
 * <p>Với cấu hình production Product bị cắt tối đa 400 ms nên điều kiện này chỉ xảy ra khi
 * request đã tốn thời gian trước pha Product. Test tái tạo đúng tình huống đó bằng cách
 * nới trần timeout Product và cho Product chậm: sau Product còn khoảng 55 ms, nằm giữa
 * {@code compose-reserve} (10 ms) và ngưỡng bỏ pha (100 ms).
 */
@TestPropertySource(properties = {
        "pdp.deadline=600ms",
        "pdp.compose-reserve=10ms",
        "pdp.product.timeout=1000ms"
})
class PhaseTwoBudgetTest extends AbstractPdpTest {

    @Test
    @DisplayName("Hết budget sau Product: không gọi Inventory/Review, mọi SKU UNKNOWN, review UNAVAILABLE")
    void exhaustedBudgetSkipsPhaseTwo() {
        stubHealthyUpstreams();
        stubProduct(json(Fixtures.product()).withFixedDelay(545));
        long inventorySkipped = calls(Dependency.INVENTORY, "budget_skipped");
        long reviewSkipped = calls(Dependency.REVIEW, "budget_skipped");

        var body = bodyOf(getPdp("smart-watch").expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store"));

        assertThat(availabilityStatuses(body)).containsExactly("UNKNOWN", "UNKNOWN", "UNKNOWN");
        assertThat(body.path("review_section").path("status").asText()).isEqualTo("UNAVAILABLE");

        UPSTREAMS.verify(0, postRequestedFor(urlEqualTo(INVENTORY_PATH)));
        UPSTREAMS.verify(0, getRequestedFor(urlPathEqualTo(REVIEW_PATH)));
        assertThat(calls(Dependency.INVENTORY, "budget_skipped") - inventorySkipped).isEqualTo(1);
        assertThat(calls(Dependency.REVIEW, "budget_skipped") - reviewSkipped).isEqualTo(1);
        assertThat(guard.breaker(Dependency.INVENTORY).getMetrics().getNumberOfBufferedCalls()).isZero();
    }
}
