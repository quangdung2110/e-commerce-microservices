package com.ecommerce.review.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Refuses to start outside a development profile while a Review secret is missing, short, a
 * placeholder or a value committed for the local stack (security review 2026-10-22, H1): the two
 * HMAC secrets pseudonymise authors and guard deletions, and the object-store credentials must be
 * Review's own, never the MinIO root user. docker-compose runs the {@code local} profile with its
 * development values; every other environment reads them from the secret manager.
 */
@Configuration(proxyBeanMethods = false)
class StartupSecretGuard {

    static final Set<String> COMMITTED_VALUES = Set.of(
            "dev-review-author-alias-secret", "dev-review-privacy-guard-secret",
            "test-review-author-alias-secret", "test-review-privacy-guard-secret",
            "minioadmin", "review-service", "review-service-local-dev");

    StartupSecretGuard(Environment environment) {
        if (SecretPolicy.isDevelopment(environment)) {
            return;
        }
        List<String> problems = new ArrayList<>();
        SecretPolicy.problem("review.secrets.author-alias-hmac (REVIEW_AUTHOR_ALIAS_HMAC_SECRET)",
                environment.getProperty("review.secrets.author-alias-hmac"), 32, COMMITTED_VALUES).ifPresent(problems::add);
        SecretPolicy.problem("review.secrets.privacy-guard-hmac (REVIEW_PRIVACY_GUARD_HMAC_SECRET)",
                environment.getProperty("review.secrets.privacy-guard-hmac"), 32, COMMITTED_VALUES).ifPresent(problems::add);
        SecretPolicy.problem("review.storage.access-key (REVIEW_S3_ACCESS_KEY)",
                environment.getProperty("review.storage.access-key"), 3, COMMITTED_VALUES).ifPresent(problems::add);
        SecretPolicy.problem("review.storage.secret-key (REVIEW_S3_SECRET_KEY)",
                environment.getProperty("review.storage.secret-key"), 20, COMMITTED_VALUES).ifPresent(problems::add);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start outside a development profile: "
                    + String.join("; ", problems));
        }
    }
}
