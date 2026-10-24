package com.ecommerce.pdp;

import com.ecommerce.pdp.resilience.Dependency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/** A fresh application, without the manual public requests used by the older test fixture. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class StartupFirstRequestTest extends AbstractPdpTest {
    @Override
    @BeforeEach
    void resetUpstreams() {
        UPSTREAMS.resetAll();
        for (Dependency dependency : Dependency.values()) {
            guard.breaker(dependency).reset();
        }
        stubHealthyUpstreams();
    }

    @Test
    void firstRequestWorksAndMissingProductStillDiffersFromTimeout() {
        assertThat(meters.get("pdp.client.startup.warmup").tag("outcome", "success").timer().count()).isEqualTo(1);
        getPdp("smart-watch").expectStatus().value(status -> assertThat(status)
                .as("First PDP dependency timers: %s", meters.getMeters().stream()
                        .filter(m -> m.getId().getName().equals("pdp.dependency.calls"))
                        .map(m -> m.getId().getTags() + " " + m.measure()).toList())
                .isEqualTo(200)).expectBody().returnResult();

        UPSTREAMS.stubFor(get(urlEqualTo(PRODUCT_PATH)).willReturn(aResponse().withStatus(404)));
        getPdp("smart-watch").expectStatus().isNotFound();
        UPSTREAMS.stubFor(get(urlEqualTo(PRODUCT_PATH)).willReturn(aResponse().withFixedDelay(600).withStatus(404)));
        getPdp("smart-watch").expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Cache-Control", "no-store");
    }
}
