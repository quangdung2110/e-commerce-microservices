package com.ecommerce.pdp.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** TTL ngoài 1–60 giây là độ trễ catalog không ai chốt (ADR-011): ứng dụng không khởi động. */
class CatalogCachePropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(CatalogCacheProperties.class)
    static class Properties {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Properties.class);

    @Test
    @DisplayName("Không cấu hình gì: bật, TTL 10 giây, trần 64 MB")
    void defaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            CatalogCacheProperties properties = context.getBean(CatalogCacheProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.ttl()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.maxWeight().toMegabytes()).isEqualTo(64);
        });
    }

    @Test
    @DisplayName("TTL 0 hoặc quá 60 giây khi bật thì không khởi động")
    void unboundedTtlFails() {
        runner.withPropertyValues("pdp.catalog-cache.ttl=0s").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("pdp.catalog-cache.ttl=5m").run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("Trần bộ nhớ dưới 1 MB khi bật thì không khởi động")
    void tinyWeightFails() {
        runner.withPropertyValues("pdp.catalog-cache.max-weight=100KB")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("Tắt cache thì TTL không được kiểm")
    void disabledCacheIgnoresTtl() {
        runner.withPropertyValues("pdp.catalog-cache.enabled=false", "pdp.catalog-cache.ttl=0s")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
