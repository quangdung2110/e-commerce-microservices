package com.ecommerce.review.support;

import com.ecommerce.review.config.ReviewProperties;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** A fully populated {@link ReviewProperties} for tests that do not boot the context. */
public final class TestProperties {

    private TestProperties() {
    }

    public static ReviewProperties defaults() {
        return withSecrets("test-alias-secret", "test-guard-secret");
    }

    public static ReviewProperties withSecrets(String aliasSecret, String guardSecret) {
        return new ReviewProperties(30, 5000, 9, 10, Duration.ofMinutes(30), 30, 30,
                new ReviewProperties.Secrets(aliasSecret, guardSecret),
                new ReviewProperties.Media(Duration.ofMinutes(15), 18, 536870912L, 10485760L, 104857600L,
                        Set.of("image/jpeg", "image/png"), Set.of("video/mp4"),
                        Duration.ofMinutes(5), Duration.ofMinutes(2)),
                new ReviewProperties.Storage("http://localhost:9000", "", "us-east-1", "review-media",
                        "key", "secret", true),
                new ReviewProperties.Gateway("X-User-Id", "X-User-Roles", "X-Request-Id", "ADMIN"),
                new ReviewProperties.Internal(List.of(), List.of()),
                new ReviewProperties.Topics("t.review.events", "t.order.events", "t.auth.events"),
                new ReviewProperties.ConsumerGroups("order", "privacy", "rating"),
                new ReviewProperties.Scheduler(false, "-", "-", 500));
    }
}
