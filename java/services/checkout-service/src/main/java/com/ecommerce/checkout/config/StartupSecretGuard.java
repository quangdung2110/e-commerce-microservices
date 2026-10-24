package com.ecommerce.checkout.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Refuses to start outside a development profile while a token HMAC key or an encryption key is
 * missing, short, a placeholder or a key committed for the local stack (security review 2026-10-22,
 * H1): with a known HMAC key anyone forges a checkout token, with a known encryption key anyone
 * reads the session envelope. Each entry of the versioned lists ({@code v1:<base64>,v2:...}) is
 * checked. docker-compose runs the {@code local} profile with its development keys; every other
 * environment reads them from the secret manager.
 */
@Configuration(proxyBeanMethods = false)
class StartupSecretGuard {

    static final Set<String> COMMITTED_VALUES = Set.of(
            // docker-compose.yml CHECKOUT_TOKEN_HMAC_KEYS / CHECKOUT_ENCRYPTION_KEYS (version v1)
            "bG9jYWwtZGV2LW9ubHktY2hlY2tvdXQtaG1hYy1rZXktMDAwMSE=",
            "bG9jYWwtZGV2LW9ubHktY2hlY2tvdXQtYWVzLWtleSE=");

    StartupSecretGuard(Environment environment) {
        if (SecretPolicy.isDevelopment(environment)) {
            return;
        }
        List<String> problems = new ArrayList<>();
        checkVersionedKeys("checkout.keys.token-hmac-keys (CHECKOUT_TOKEN_HMAC_KEYS)",
                environment.getProperty("checkout.keys.token-hmac-keys"), problems);
        checkVersionedKeys("checkout.keys.encryption-keys (CHECKOUT_ENCRYPTION_KEYS)",
                environment.getProperty("checkout.keys.encryption-keys"), problems);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start outside a development profile: "
                    + String.join("; ", problems));
        }
    }

    private static void checkVersionedKeys(String name, String configured, List<String> problems) {
        if (configured == null || configured.isBlank()) {
            problems.add(name + " is required outside development");
            return;
        }
        for (String entry : configured.split(",")) {
            String trimmed = entry.trim();
            int separator = trimmed.indexOf(':');
            String version = separator < 0 ? "?" : trimmed.substring(0, separator);
            String key = separator < 0 ? trimmed : trimmed.substring(separator + 1);
            // 32 bytes of base64 is 44 characters.
            SecretPolicy.problem(name + " " + version, key, 44, COMMITTED_VALUES).ifPresent(problems::add);
        }
    }
}
