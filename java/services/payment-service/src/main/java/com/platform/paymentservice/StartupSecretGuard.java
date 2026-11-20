package com.platform.paymentservice;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Refuses to start while a Stripe secret is a placeholder or a value committed for the local stack
 * (security review 2026-10-22, H1). The webhook route is public through the gateway, so whoever
 * knows the webhook signing secret can sign a {@code payment_intent.succeeded} of their own.
 *
 * <ul>
 *   <li>Outside a development profile every configured Stripe secret must be real: not blank,
 *       not a placeholder, not committed, long enough. An unset secret leaves its beans out
 *       (the webhook route then fails closed), so only a configured one is checked.</li>
 *   <li>In a development profile a placeholder webhook secret is accepted only while the Stripe
 *       API key is a placeholder too: that Payment cannot create a real PaymentIntent, so a forged
 *       event has nothing to confirm. Setting a real sandbox key without the {@code stripe listen}
 *       secret stops the service instead of leaving the webhook signable by anyone.</li>
 * </ul>
 *
 * {@code payment.crypto.local-key} is refused outside dev/test by {@code LocalCryptoConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
class StartupSecretGuard {

    static final Set<String> COMMITTED_VALUES = Set.of(
            "sk_test_local_change_me", "pk_test_local_change_me", "whsec_local_change_me",
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

    private static final String SECRET_KEY = "payment.stripe.secret-key";
    private static final String WEBHOOK_SECRETS = "payment.stripe.webhook-secrets";

    StartupSecretGuard(Environment environment) {
        List<String> problems = new ArrayList<>();
        String secretKey = environment.getProperty(SECRET_KEY);
        String webhookSecrets = environment.getProperty(WEBHOOK_SECRETS);
        if (environment.matchesProfiles("test")) {
            // The suite signs its own fixtures with short fixed secrets.
            return;
        }
        if (SecretPolicy.isDevelopment(environment)) {
            if (webhookSecrets != null && secretKey != null
                    && weak(WEBHOOK_SECRETS, webhookSecrets) && !weak(SECRET_KEY, secretKey)) {
                problems.add(WEBHOOK_SECRETS + " (PAYMENT_STRIPE_WEBHOOK_SECRETS) is a placeholder while "
                        + SECRET_KEY + " is a real Stripe key: set the secret printed by `stripe listen`");
            }
        } else {
            if (secretKey != null) {
                SecretPolicy.problem(SECRET_KEY + " (PAYMENT_STRIPE_SECRET_KEY)", secretKey, 20, COMMITTED_VALUES)
                        .ifPresent(problems::add);
            }
            if (webhookSecrets != null) {
                for (String secret : webhookSecrets.split(",", -1)) {
                    SecretPolicy.problem(WEBHOOK_SECRETS + " (PAYMENT_STRIPE_WEBHOOK_SECRETS)", secret, 20,
                            COMMITTED_VALUES).ifPresent(problems::add);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start: " + String.join("; ", problems));
        }
    }

    private static boolean weak(String name, String configured) {
        for (String value : configured.split(",", -1)) {
            if (SecretPolicy.problem(name, value, 20, COMMITTED_VALUES).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
