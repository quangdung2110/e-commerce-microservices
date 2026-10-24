package com.ecommerce.checkout;

import com.ecommerce.checkout.resilience.Dependency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Does not send fixture warm-up Preview calls before the first assertion. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class StartupFirstRequestTest extends AbstractCheckoutTest {
    @Override
    @BeforeEach
    void resetWorld() {
        UPSTREAMS.resetAll();
        stubPromotionReadiness();
        PRODUCT.reset();
        clock.reset();
        for (Dependency dependency : Dependency.values()) {
            guard.breaker(dependency).reset();
        }
        client = client.mutate().responseTimeout(Duration.ofSeconds(40)).build();
        stubHealthy();
    }

    @Test
    void firstValidPreviewWorksAndDoesNotSubmitAnOrder() {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() ->
                client.get().uri("/health/ready").exchange().returnResult(String.class).getStatus().value() == 200);
        assertThat(meters.get("checkout.client.startup.warmup").tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(meters.get("checkout.redis.startup.warmup").tag("outcome", "success").timer().count()).isEqualTo(1);
        preview(newUser()).expectStatus().value(status -> assertThat(status)
                .as("First Preview dependency timers: %s", meters.getMeters().stream()
                        .filter(m -> m.getId().getName().equals("checkout.dependency.calls")
                                || m.getId().getName().equals("checkout.client.decode")
                                || m.getId().getName().equals("reactor.netty.http.client.response.time"))
                        .map(m -> m.getId().getTags() + " " + m.measure()).toList())
                .isEqualTo(200)).expectBody().jsonPath("$.items.length()").isEqualTo(2);
        assertThat(PRODUCT.calls()).isEqualTo(1);
        UPSTREAMS.verify(0, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(ORDER_PATH)));
    }
}
