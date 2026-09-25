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
 * Cờ {@code PDP_INVENTORY_ENABLED}/{@code PDP_REVIEW_ENABLED} (ADR-007): trước rollout gate
 * BFF không gọi dependency bị tắt, outcome là {@code disabled} để SLO degrade và alert không
 * tính nó. Review tắt thì không cần token.
 */
@TestPropertySource(properties = {
        "pdp.inventory.enabled=false",
        "pdp.review.enabled=false",
        "pdp.review.service-token="
})
class DisabledDependenciesTest extends AbstractPdpTest {

    @Test
    @DisplayName("Dependency bị tắt không nhận request nào, SKU UNKNOWN và review UNAVAILABLE với nguồn disabled")
    void disabledDependenciesAreNeverCalled() {
        stubHealthyUpstreams();
        double unknownBefore = counter("pdp.sku.unknown", "source", "disabled");
        double unavailableBefore = counter("pdp.review.unavailable", "source", "disabled");
        long inventoryDisabled = calls(Dependency.INVENTORY, "disabled");

        var body = bodyOf(getPdp("smart-watch").expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store"));

        assertThat(availabilityStatuses(body)).containsExactly("UNKNOWN", "UNKNOWN", "UNKNOWN");
        assertThat(body.path("review_section").path("status").asText()).isEqualTo("UNAVAILABLE");

        UPSTREAMS.verify(0, postRequestedFor(urlEqualTo(INVENTORY_PATH)));
        UPSTREAMS.verify(0, getRequestedFor(urlPathEqualTo(REVIEW_PATH)));
        assertThat(counter("pdp.sku.unknown", "source", "disabled") - unknownBefore).isEqualTo(3);
        assertThat(counter("pdp.review.unavailable", "source", "disabled") - unavailableBefore).isEqualTo(1);
        assertThat(calls(Dependency.INVENTORY, "disabled") - inventoryDisabled).isEqualTo(1);
    }
}
