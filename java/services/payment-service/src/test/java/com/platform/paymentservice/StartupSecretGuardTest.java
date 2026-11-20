package com.platform.paymentservice;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Security review 2026-10-22, H1: Stripe secrets and the public webhook route. */
class StartupSecretGuardTest {

    private static final String REAL_KEY = "sk_test_51Qx8TnW2vB6kR1pW9jL3hF7dS5gC0zY2aE4mN8qT";
    private static final String REAL_WEBHOOK = "whsec_7Hq2mT9xK4bN1wR6vL3pJ8sF5dG0cZ2y";

    private static MockEnvironment environment(String profile, String secretKey, String webhookSecrets) {
        MockEnvironment environment = new MockEnvironment();
        if (profile != null) {
            environment.setActiveProfiles(profile);
        }
        if (secretKey != null) {
            environment.setProperty("payment.stripe.secret-key", secretKey);
        }
        if (webhookSecrets != null) {
            environment.setProperty("payment.stripe.webhook-secrets", webhookSecrets);
        }
        return environment;
    }

    @Test
    void productionWithRealSecretsStarts() {
        assertThatCode(() -> new StartupSecretGuard(environment("prod", REAL_KEY, REAL_WEBHOOK + "," + REAL_WEBHOOK)))
                .doesNotThrowAnyException();
    }

    @Test
    void productionRefusesTheComposePlaceholders() {
        assertThatThrownBy(() -> new StartupSecretGuard(environment("prod", "sk_test_local_change_me", REAL_WEBHOOK)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYMENT_STRIPE_SECRET_KEY");
        assertThatThrownBy(() -> new StartupSecretGuard(environment(null, REAL_KEY, "whsec_local_change_me")))
                .hasMessageContaining("PAYMENT_STRIPE_WEBHOOK_SECRETS");
    }

    @Test
    void productionRefusesABlankSecondWebhookSecret() {
        assertThatThrownBy(() -> new StartupSecretGuard(environment("prod", REAL_KEY, REAL_WEBHOOK + ",")))
                .hasMessageContaining("PAYMENT_STRIPE_WEBHOOK_SECRETS) is required");
    }

    @Test
    void productionWithoutStripeLeavesTheBeansOut() {
        assertThatCode(() -> new StartupSecretGuard(environment("prod", null, null))).doesNotThrowAnyException();
    }

    @Test
    void developmentAcceptsPlaceholdersThatTravelTogether() {
        assertThatCode(() -> new StartupSecretGuard(
                environment("dev", "sk_test_local_change_me", "whsec_local_change_me"))).doesNotThrowAnyException();
        assertThatCode(() -> new StartupSecretGuard(environment("dev", REAL_KEY, REAL_WEBHOOK))).doesNotThrowAnyException();
    }

    @Test
    void theTestProfileKeepsItsFixtureSecrets() {
        assertThatCode(() -> new StartupSecretGuard(environment("test", "sk_test_context_only", "whsec_context_only")))
                .doesNotThrowAnyException();
    }

    @Test
    void developmentRefusesARealKeyWithAPlaceholderWebhookSecret() {
        assertThatThrownBy(() -> new StartupSecretGuard(environment("dev", REAL_KEY, "whsec_local_change_me")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stripe listen");
    }
}
