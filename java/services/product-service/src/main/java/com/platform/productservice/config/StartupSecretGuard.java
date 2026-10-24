package com.platform.productservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Set;

/**
 * Refuses to start outside a development profile while the export cursor secret is missing, short,
 * a placeholder or the value committed for the local stack (security review 2026-10-22, H1). A
 * known secret lets any caller of the internal export forge a cursor. docker-compose runs the
 * {@code local} profile with its own development value; every other environment sets
 * {@code EXPORT_CURSOR_SECRET} from the secret manager.
 */
@Configuration(proxyBeanMethods = false)
class StartupSecretGuard {

    static final Set<String> COMMITTED_VALUES = Set.of(
            "dev-export-cursor-secret", "dev-export-cursor-secret-change-in-prod", "test-export-cursor-secret");

    StartupSecretGuard(Environment environment) {
        if (SecretPolicy.isDevelopment(environment)) {
            return;
        }
        List<String> problems = SecretPolicy.problem("product.export.cursor-secret (EXPORT_CURSOR_SECRET)",
                environment.getProperty("product.export.cursor-secret"), 32, COMMITTED_VALUES).stream().toList();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start outside a development profile: "
                    + String.join("; ", problems));
        }
    }
}
