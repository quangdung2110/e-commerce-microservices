package com.ecommerce.pdp;

import com.ecommerce.pdp.resilience.Dependency;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hành vi ở biên của BFF mà contract hứa nhưng không đi qua pipeline dependency: probe,
 * đường dẫn không phải slug và gắn request ID vào log (operations.md §Health,
 * §Observability; interfaces.md).
 */
@ExtendWith(OutputCaptureExtension.class)
class EdgeBehaviourTest extends AbstractPdpTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Readiness UP kể cả khi cả ba circuit đang mở")
    void readinessIgnoresOpenCircuits() {
        for (Dependency dependency : Dependency.values()) {
            guard.breaker(dependency).transitionToOpenState();
        }

        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("Graceful shutdown đưa readiness về OUT_OF_SERVICE để Gateway ngừng gửi request")
    void readinessLeavesServiceWhileRefusingTraffic() {
        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);

            client.get().uri("/actuator/health/readiness").exchange().expectStatus().isEqualTo(503)
                    .expectBody().jsonPath("$.status").isEqualTo("OUT_OF_SERVICE");
            client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    @Test
    @DisplayName("Đường dẫn dưới prefix PDP mà không phải một slug là 404 problem no-store, không gọi dependency")
    void pathsThatAreNotASlugAreProblemNotFound() {
        stubHealthyUpstreams();

        for (String path : List.of("/api/v1/storefront/products/", "/api/v1/storefront/products/smart-watch/",
                "/api/v1/storefront/products/a/b", "/api/v1/storefront/products/smart-watch/reviews")) {
            JsonNode problem = bodyOf(client.get().uri(path).exchange()
                    .expectStatus().isNotFound()
                    .expectHeader().valueEquals("Cache-Control", "no-store")
                    .expectHeader().contentType("application/problem+json"));

            assertThat(ContractSchemas.validate("Problem", problem)).as(path).isEmpty();
            assertThat(problem.path("code").asText()).as(path).isEqualTo("PRODUCT_DETAIL_NOT_FOUND");
            assertThat(problem.path("retryable").asBoolean()).as(path).isFalse();
        }
        UPSTREAMS.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("Dòng log cảnh báo dependency mang request_id và trace_id của request")
    void dependencyWarningsCarryTheRequestId(CapturedOutput output) {
        stubProduct(Fixtures.product());
        stubInventory(status(403));
        stubReview(Fixtures.review());

        client.get().uri("/api/v1/storefront/products/smart-watch")
                .header("X-Request-Id", "req-log-12345678")
                .exchange().expectStatus().isOk();

        assertThat(output.getAll())
                .contains("inventory answered 403")
                .contains("request_id=req-log-12345678")
                .containsPattern("trace_id=[0-9a-f]{32}");
    }

    @Test
    @DisplayName("Item Inventory thiếu hoặc thừa ghi một dòng log kèm request_id")
    void inventoryItemViolationsAreLoggedWithTheRequestId(CapturedOutput output) {
        stubProduct(Fixtures.product());
        stubInventory(Fixtures.inventory(List.of(
                Fixtures.availability(Fixtures.SKU_BLACK_42, "IN_STOCK"),
                Fixtures.availability("sku-not-requested", "IN_STOCK"))));
        stubReview(Fixtures.review());

        client.get().uri("/api/v1/storefront/products/smart-watch")
                .header("X-Request-Id", "req-item-12345678")
                .exchange().expectStatus().isOk();

        assertThat(output.getAll())
                .contains("requested=3 missing=2 extra=1")
                .contains("request_id=req-item-12345678");
    }
}
