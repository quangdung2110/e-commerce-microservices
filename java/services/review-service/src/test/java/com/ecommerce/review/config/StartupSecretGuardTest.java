package com.ecommerce.review.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Security review 2026-10-22, H1: Review secrets outside development. */
class StartupSecretGuardTest {

    private static MockEnvironment production() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("review.secrets.author-alias-hmac", "N4mQ8xT2vB6kR1pW9jL3hF7dS5gC0zY2aE");
        environment.setProperty("review.secrets.privacy-guard-hmac", "H7tK2wP9rM4xB1nV6jQ3sL8dF5gZ0cY7uA");
        environment.setProperty("review.storage.access-key", "review-svc-7Q2");
        environment.setProperty("review.storage.secret-key", "s3cR8kW2nT6vB1mX4pL9hJ3d");
        return environment;
    }

    @Test
    void productionWithRealSecretsStarts() {
        assertThatCode(() -> new StartupSecretGuard(production())).doesNotThrowAnyException();
    }

    @Test
    void productionRefusesTheDevelopmentHmacSecrets() {
        MockEnvironment environment = production();
        environment.setProperty("review.secrets.author-alias-hmac", "dev-review-author-alias-secret");
        assertThatThrownBy(() -> new StartupSecretGuard(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REVIEW_AUTHOR_ALIAS_HMAC_SECRET");
    }

    @Test
    void productionRefusesAMissingOrShortHmacSecret() {
        MockEnvironment missing = production();
        missing.setProperty("review.secrets.privacy-guard-hmac", "");
        assertThatThrownBy(() -> new StartupSecretGuard(missing)).hasMessageContaining("REVIEW_PRIVACY_GUARD_HMAC_SECRET");

        MockEnvironment shortSecret = production();
        shortSecret.setProperty("review.secrets.privacy-guard-hmac", "H7tK2wP9rM4x");
        assertThatThrownBy(() -> new StartupSecretGuard(shortSecret)).hasMessageContaining("at least 32");
    }

    @Test
    void productionRefusesTheMinioRootUser() {
        MockEnvironment environment = production();
        environment.setProperty("review.storage.access-key", "minioadmin");
        environment.setProperty("review.storage.secret-key", "minioadmin");
        assertThatThrownBy(() -> new StartupSecretGuard(environment))
                .hasMessageContaining("REVIEW_S3_ACCESS_KEY")
                .hasMessageContaining("REVIEW_S3_SECRET_KEY");
    }

    @Test
    void productionRefusesTheLocalReviewUser() {
        MockEnvironment environment = production();
        environment.setProperty("review.storage.secret-key", "review-service-local-dev");
        assertThatThrownBy(() -> new StartupSecretGuard(environment)).hasMessageContaining("REVIEW_S3_SECRET_KEY");
    }

    @Test
    void localProfileAcceptsDevelopmentValues() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "local");
        environment.setProperty("review.secrets.author-alias-hmac", "dev-review-author-alias-secret");
        environment.setProperty("review.storage.access-key", "review-service");
        assertThatCode(() -> new StartupSecretGuard(environment)).doesNotThrowAnyException();
    }
}
