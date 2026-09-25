package com.ecommerce.pdp.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cấu hình thiếu hoặc sai thì ứng dụng không khởi động, tức không bao giờ ready
 * (operations.md §Health và degradation).
 */
class PdpPropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(PdpProperties.class)
    static class Properties {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Properties.class)
            .withPropertyValues(
                    "pdp.deadline=1000ms",
                    "pdp.phase-min-remaining=100ms",
                    "pdp.compose-reserve=50ms",
                    "pdp.full-response-cache-control=no-store",
                    "pdp.product.enabled=true",
                    "pdp.product.base-url=http://product-service:8080",
                    "pdp.product.timeout=400ms",
                    "pdp.product.bulkhead=100",
                    "pdp.product.slow-call-threshold=320ms",
                    "pdp.inventory.enabled=false",
                    "pdp.inventory.timeout=300ms",
                    "pdp.inventory.bulkhead=80",
                    "pdp.inventory.slow-call-threshold=240ms",
                    "pdp.review.enabled=true",
                    "pdp.review.base-url=http://review-service:8080",
                    "pdp.review.service-token=secret",
                    "pdp.review.preview-limit=3",
                    "pdp.review.timeout=300ms",
                    "pdp.review.bulkhead=80",
                    "pdp.review.slow-call-threshold=240ms",
                    "pdp.circuit-breaker.sliding-window=10s",
                    "pdp.circuit-breaker.minimum-calls=20",
                    "pdp.circuit-breaker.failure-rate-threshold=50",
                    "pdp.circuit-breaker.slow-call-rate-threshold=50",
                    "pdp.circuit-breaker.open-duration=10s",
                    "pdp.circuit-breaker.half-open-calls=5",
                    "pdp.media.cdn-base-url=https://cdn.example.com/media",
                    "pdp.media.placeholder-url=https://cdn.example.com/placeholder.png");

    @Test
    @DisplayName("Cấu hình đầy đủ khởi động được; Inventory tắt thì không cần base URL")
    void completeConfigurationStarts() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("Review bật mà thiếu token thì không khởi động")
    void reviewWithoutTokenFails() {
        runner.withPropertyValues("pdp.review.service-token=")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("PDP_REVIEW_SERVICE_TOKEN"));
    }

    @Test
    @DisplayName("Inventory bật mà thiếu base URL thì không khởi động")
    void enabledInventoryWithoutBaseUrlFails() {
        runner.withPropertyValues("pdp.inventory.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("CDN hoặc placeholder không phải https tuyệt đối thì không khởi động")
    void insecureOrRelativeMediaUrlsFail() {
        runner.withPropertyValues("pdp.media.cdn-base-url=http://cdn.example.com")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("pdp.media.cdn-base-url=https://cdn.example.com/")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("pdp.media.placeholder-url=/placeholder.png")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("preview_limit ngoài 1..5 thì không khởi động")
    void previewLimitOutOfRangeFails() {
        runner.withPropertyValues("pdp.review.preview-limit=6")
                .run(context -> assertThat(context).hasFailed());
    }
}
