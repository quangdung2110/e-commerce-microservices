package com.ecommerce.checkout.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Security review 2026-10-22, H1: checkout token and envelope keys outside development. */
class StartupSecretGuardTest {

    // 32 random bytes each, base64.
    private static final String HMAC = "v1:3q2+7wAAAAC8yXkR0p9mT4LwQ7bN1sV6jH2fK8dZcE0=";
    private static final String AES = "v1:pV7mQ2xT9kB4nR1wL6hJ3sF8dG5cZ0yA2eU7iO4tN1M=";

    private static MockEnvironment environment(String hmacKeys, String encryptionKeys, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        if (hmacKeys != null) {
            environment.setProperty("checkout.keys.token-hmac-keys", hmacKeys);
        }
        if (encryptionKeys != null) {
            environment.setProperty("checkout.keys.encryption-keys", encryptionKeys);
        }
        return environment;
    }

    @Test
    @DisplayName("no profile with random keys starts")
    void randomKeysStart() {
        assertThatCode(() -> new StartupSecretGuard(environment(HMAC, AES))).doesNotThrowAnyException();
        assertThatCode(() -> new StartupSecretGuard(environment(HMAC + "," + HMAC.replace("v1", "v2"), AES, "prod")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the keys committed in docker-compose.yml do not start outside development")
    void committedKeysAreRefused() {
        assertThatThrownBy(() -> new StartupSecretGuard(environment(
                "v1:bG9jYWwtZGV2LW9ubHktY2hlY2tvdXQtaG1hYy1rZXktMDAwMSE=",
                "v1:bG9jYWwtZGV2LW9ubHktY2hlY2tvdXQtYWVzLWtleSE=")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHECKOUT_TOKEN_HMAC_KEYS) v1 is the development value")
                .hasMessageContaining("CHECKOUT_ENCRYPTION_KEYS) v1 is the development value");
    }

    @Test
    @DisplayName("an older version that is a placeholder is refused too")
    void everyVersionIsChecked() {
        String placeholder = java.util.Base64.getEncoder().encodeToString("change_me-change_me-change_me-32".getBytes());
        assertThatThrownBy(() -> new StartupSecretGuard(environment(HMAC + ",v0:" + placeholder, AES)))
                .hasMessageContaining("CHECKOUT_TOKEN_HMAC_KEYS) v0 looks like a placeholder");
    }

    @Test
    @DisplayName("missing or short keys are refused")
    void missingOrShortKeysAreRefused() {
        assertThatThrownBy(() -> new StartupSecretGuard(environment(null, AES)))
                .hasMessageContaining("CHECKOUT_TOKEN_HMAC_KEYS) is required");
        assertThatThrownBy(() -> new StartupSecretGuard(environment(HMAC, "v1:pV7mQ2xT9kB4nR1w")))
                .hasMessageContaining("CHECKOUT_ENCRYPTION_KEYS) v1 must be at least 44 characters");
    }

    @Test
    @DisplayName("the local profile of docker-compose keeps its development keys")
    void localProfileAcceptsDevelopmentKeys() {
        assertThatCode(() -> new StartupSecretGuard(environment(
                "v1:bG9jYWwtZGV2LW9ubHktY2hlY2tvdXQtaG1hYy1rZXktMDAwMSE=",
                "v1:bG9jYWwtZGV2LW9ubHktY2hlY2tvdXQtYWVzLWtleSE=", "local"))).doesNotThrowAnyException();
    }
}
