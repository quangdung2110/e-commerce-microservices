package com.ecommerce.checkout.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A mis-configured instance must never become ready (operations.md): startup validation refuses missing
 * secrets and URLs, and the two timeout invariants of CHK-INV-07 (Checkout timeout below the mesh timeout,
 * deadline at least one second below the Gateway wait).
 */
class CheckoutPropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(CheckoutProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    private static List<String> valid() {
        List<String> properties = new ArrayList<>(List.of(
                "checkout.redis-url=redis://localhost:6379",
                "checkout.keys.token-hmac-keys=v1:aGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGg=",
                "checkout.keys.token-hmac-active-version=v1",
                "checkout.keys.encryption-keys=v1:a2tra2tra2tra2tra2tra2tra2tra2tra2tra2tra2s=",
                "checkout.keys.encryption-active-version=v1",
                "checkout.endpoints.cart-base-url=http://cart:8080",
                "checkout.endpoints.user-base-url=http://user:8083",
                "checkout.endpoints.product-grpc-target=product:9091",
                "checkout.endpoints.inventory-base-url=http://inventory:8080",
                "checkout.endpoints.promotion-base-url=http://promotion:8085",
                "checkout.endpoints.order-base-url=http://order:8080",
                "checkout.media.cdn-base-url=https://cdn.test/media",
                "checkout.media.placeholder-url=https://cdn.test/placeholder.png",
                "checkout.deadlines.preview=5000ms", "checkout.deadlines.voucher-options=1500ms",
                "checkout.deadlines.submit=20000ms",
                "checkout.phase-min-remaining=200ms", "checkout.dependency-reserve=100ms",
                "checkout.order-reserve=200ms", "checkout.order-min-remaining=2000ms", "checkout.redis-timeout=200ms",
                "checkout.timeouts.cart=500ms", "checkout.timeouts.user=500ms", "checkout.timeouts.product=1000ms",
                "checkout.timeouts.inventory=3000ms", "checkout.timeouts.promotion-calculation=2000ms",
                "checkout.timeouts.promotion-options=1000ms", "checkout.timeouts.order-post=13000ms",
                "checkout.timeouts.order-get=2000ms",
                "checkout.bulkheads.cart=10", "checkout.bulkheads.user=10", "checkout.bulkheads.product=10",
                "checkout.bulkheads.inventory=20", "checkout.bulkheads.promotion-calculation=15",
                "checkout.bulkheads.promotion-options=10", "checkout.bulkheads.order-post=50",
                "checkout.bulkheads.order-get=10",
                "checkout.circuit-breaker.sliding-window=10s", "checkout.circuit-breaker.minimum-calls=20",
                "checkout.circuit-breaker.failure-rate-threshold=50",
                "checkout.circuit-breaker.slow-call-rate-threshold=50",
                "checkout.circuit-breaker.slow-call-timeout-ratio=0.8", "checkout.circuit-breaker.open-duration=10s",
                "checkout.circuit-breaker.half-open-calls=5",
                "checkout.lifecycle.quote-ttl=20m", "checkout.lifecycle.session-retention=30m",
                "checkout.lifecycle.preview-idempotency-ttl=5m", "checkout.lifecycle.submit-owner-timeout=30s",
                "checkout.lifecycle.submit-retry-window=2m", "checkout.lifecycle.order-call-in-flight-window=20s",
                "checkout.lifecycle.success-result-ttl=24h",
                "checkout.retry-after.preparing-seconds=2", "checkout.retry-after.result-unknown-seconds=3",
                "checkout.retry-after.unavailable-seconds=2"));
        return properties;
    }

    private boolean starts(String... overrides) {
        List<String> properties = valid();
        for (String override : overrides) {
            String key = override.substring(0, override.indexOf('='));
            properties.removeIf(property -> property.startsWith(key + "="));
            properties.add(override);
        }
        boolean[] started = new boolean[1];
        runner.withPropertyValues(properties.toArray(String[]::new))
                .run(context -> started[0] = context.getStartupFailure() == null);
        return started[0];
    }

    @Test
    @DisplayName("The documented defaults are a valid configuration")
    void defaultsAreValid() {
        assertThat(starts()).isTrue();
    }

    @Test
    @DisplayName("Missing secrets, URLs or CDN settings stop the instance from starting")
    void missingRequiredSettings() {
        assertThat(starts("checkout.redis-url=")).isFalse();
        assertThat(starts("checkout.keys.token-hmac-keys=")).isFalse();
        assertThat(starts("checkout.keys.encryption-keys=")).isFalse();
        assertThat(starts("checkout.endpoints.cart-base-url=")).isFalse();
        assertThat(starts("checkout.endpoints.product-grpc-target=")).isFalse();
        assertThat(starts("checkout.media.cdn-base-url=")).isFalse();
        assertThat(starts("checkout.media.placeholder-url=")).isFalse();
    }

    @Test
    @DisplayName("Media must be https unless explicitly relaxed for local, and the CDN base has no trailing slash")
    void mediaRules() {
        assertThat(starts("checkout.media.cdn-base-url=http://localhost:8088/media")).isFalse();
        assertThat(starts("checkout.media.cdn-base-url=http://localhost:8088/media",
                "checkout.media.placeholder-url=http://localhost:8088/p.svg",
                "checkout.media.require-https=false")).isTrue();
        assertThat(starts("checkout.media.cdn-base-url=https://cdn.test/media/")).isFalse();
    }

    @Test
    @DisplayName("Every Checkout timeout must stay below its mesh timeout (CHK-INV-07)")
    void timeoutsStayBelowTheMesh() {
        assertThat(starts("checkout.timeouts.cart=1000ms")).isFalse();
        assertThat(starts("checkout.timeouts.inventory=4000ms")).isFalse();
        assertThat(starts("checkout.timeouts.order-post=15000ms")).isFalse();
        assertThat(starts("checkout.timeouts.order-get=15s")).isFalse();
        assertThat(starts("checkout.timeouts.promotion-calculation=2900ms")).isTrue();
    }

    @Test
    @DisplayName("Every deadline must be at least one second below the 25 s Gateway wait (CHK-INV-07)")
    void deadlinesStayBelowTheGateway() {
        assertThat(starts("checkout.deadlines.submit=25s")).isFalse();
        assertThat(starts("checkout.deadlines.preview=24500ms")).isFalse();
        assertThat(starts("checkout.deadlines.submit=24s", "checkout.lifecycle.submit-owner-timeout=40s")).isTrue();
    }

    @Test
    @DisplayName("The owner timeout must exceed the Submit deadline and the retry window the Order mesh timeout + 5 s")
    void recoveryWindows() {
        assertThat(starts("checkout.lifecycle.submit-owner-timeout=20s")).isFalse();
        assertThat(starts("checkout.lifecycle.submit-retry-window=20s")).isFalse();
        assertThat(starts("checkout.lifecycle.order-call-in-flight-window=19s")).isFalse();
    }

}
