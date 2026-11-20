package com.ecommerce.checkout;

import com.ecommerce.checkout.resilience.Dependency;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

/** Opt-in diagnostic: real Redis, real Promotion, real Checkout route/guard/outbound pool.
 * Cart/User/Product/Inventory use fixtures; no Order command is ever sent. */
@EnabledIfSystemProperty(named = "p1.live-promotion-url", matches = ".+")
class LivePromotionIdleTest extends AbstractCheckoutTest {
    @Override
    @BeforeEach
    void resetWorld() {
        UPSTREAMS.resetAll();
        stubPromotionReadiness();
        PRODUCT.reset();
        clock.reset();
        for (Dependency dependency : Dependency.values()) guard.breaker(dependency).reset();
        client = client.mutate().responseTimeout(Duration.ofSeconds(40)).build();
        stubHealthy();
    }

    @Test
    void firstAndFifteenMinuteIdleUseActualPromotionClientAndUnchangedGuard() throws Exception {
        String quoteUser = null, token = null;
        for (int i = 0; i < 6; i++) {
            quoteUser = newUser();
            token = measuredPreview("first", quoteUser);
        }
        for (int i = 0; i < 6; i++) measuredOptions("first", quoteUser, token);
        emit(Map.of("phase", "idle-start", "time", Instant.now().toString(), "idle_seconds", 900));
        // The shell tool is nonblocking while this diagnostic JVM waits; no keep-alive business calls.
        for (int minute = 1; minute <= 15; minute++) {
            Thread.sleep(60_000);
            emit(Map.of("phase", "idle-progress", "time", Instant.now().toString(), "idle_minutes", minute));
        }
        // Test the stale outbound pool before another Preview or business dependency can refresh it.
        for (int i = 0; i < 6; i++) measuredOptions("idle", quoteUser, token);
        for (int i = 0; i < 6; i++) measuredPreview("idle", newUser());
        UPSTREAMS.verify(0, postRequestedFor(urlEqualTo(ORDER_PATH)));
        emit(Map.of("phase", "completed", "time", Instant.now().toString(), "order_posts", 0));
    }

    private String measuredPreview(String phase, String user) throws Exception {
        double before = total("checkout.redis", "operation", "peek_preview");
        long started = System.nanoTime();
        var data = preview(user).expectStatus().isOk().expectBody().returnResult().getResponseBody();
        emit(Map.of("phase", phase, "label", "preview", "time", Instant.now().toString(), "status", 200,
                "client_ms", millis(System.nanoTime() - started), "operation_ms",
                total("checkout.redis", "operation", "peek_preview") - before));
        return JSON.readTree(data).path("checkout_token").asText();
    }

    private void measuredOptions(String phase, String user, String token) throws Exception {
        double before = total("checkout.dependency.calls", "dependency", "promotion_options");
        long started = System.nanoTime();
        voucherOptions(user, token).expectStatus().isOk().expectBody().jsonPath("$.items").isArray();
        emit(Map.of("phase", phase, "label", "voucher-options-through-checkout", "time", Instant.now().toString(),
                "status", 200, "client_ms", millis(System.nanoTime() - started), "guard_ms",
                total("checkout.dependency.calls", "dependency", "promotion_options") - before));
    }

    private double total(String name, String tag, String value) {
        return meters.find(name).tag(tag, value).timers().stream().mapToDouble(timer -> timer.totalTime(TimeUnit.MILLISECONDS)).sum();
    }
    private double millis(long nanos) { return nanos / 1_000_000d; }
    private void emit(Map<String, Object> row) throws Exception {
        System.out.println("P1_LIVE_PROBE " + JSON.writeValueAsString(row));
    }
}
