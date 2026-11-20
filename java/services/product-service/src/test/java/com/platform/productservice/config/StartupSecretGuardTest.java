package com.platform.productservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Security review 2026-10-22, H1: the export cursor secret outside development. */
class StartupSecretGuardTest {

    private static final String REAL_SECRET = "Q8vT3nW6yB1mK4pL7hR2jD5sF9gA0cE3xZ";

    private static MockEnvironment environment(String profile, String secret) {
        MockEnvironment environment = new MockEnvironment();
        if (profile != null) {
            environment.setActiveProfiles(profile);
        }
        if (secret != null) {
            environment.setProperty("product.export.cursor-secret", secret);
        }
        return environment;
    }

    @Test
    @DisplayName("prod với secret thật thì khởi động")
    void productionWithARealSecretStarts() {
        assertThatCode(() -> new StartupSecretGuard(environment("prod", REAL_SECRET))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod thiếu secret, secret dev đã commit, placeholder hoặc quá ngắn thì không khởi động")
    void productionRefusesWeakSecrets() {
        for (String weak : new String[] {null, "", "dev-export-cursor-secret",
                "dev-export-cursor-secret-change-in-prod", "my-placeholder-cursor-secret-0123456789", "short-but-random-Q8vT3"}) {
            assertThatThrownBy(() -> new StartupSecretGuard(environment("prod", weak)))
                    .as("secret %s", weak)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("product.export.cursor-secret (EXPORT_CURSOR_SECRET)");
        }
    }

    @Test
    @DisplayName("không có profile nào cũng không phải môi trường dev")
    void noProfileIsNotDevelopment() {
        assertThatThrownBy(() -> new StartupSecretGuard(environment(null, "dev-export-cursor-secret")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("profile dev, local, test chấp nhận secret dev")
    void developmentProfilesAcceptDevelopmentSecrets() {
        for (String profile : new String[] {"dev", "local", "test"}) {
            assertThatCode(() -> new StartupSecretGuard(environment(profile, "dev-export-cursor-secret")))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("placeholder giấu trong base64 vẫn bị nhận ra")
    void placeholderInsideBase64IsFound() {
        String encoded = java.util.Base64.getEncoder().encodeToString("please-change_me-now-0123456789abcd".getBytes());
        assertThat(SecretPolicy.problem("k", encoded, 32, java.util.Set.of())).get().asString().contains("placeholder");
    }
}
