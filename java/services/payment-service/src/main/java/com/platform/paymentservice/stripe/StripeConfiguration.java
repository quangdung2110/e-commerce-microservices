package com.platform.paymentservice.stripe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.port.out.PaymentProviderPort;
import com.platform.paymentservice.application.port.out.ProviderCurrentFactPort;
import com.platform.paymentservice.application.port.out.ProviderBalancePort;
import com.platform.paymentservice.application.port.out.WebhookVerificationPort;
import com.stripe.Stripe;
import com.stripe.StripeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Configuration
class StripeConfiguration {
    @Bean
    @ConditionalOnProperty(name = "payment.stripe.secret-key")
    StripeClient stripeClient(
            @Value("${payment.stripe.secret-key}") String secretKey,
            @Value("${payment.stripe.api-version}") String configuredApiVersion) {
        if (!Stripe.API_VERSION.equals(configuredApiVersion)) {
            throw new IllegalStateException("configured Stripe API version does not match pinned SDK");
        }
        return new StripeClient(secretKey);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.stripe.secret-key")
    StripeGateway stripeGateway(StripeClient client) {
        return new DefaultStripeGateway(client);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.stripe.secret-key")
    PaymentProviderPort stripePaymentProviderPort(
            StripeGateway gateway,
            ObjectMapper objectMapper,
            @Value("${payment.stripe.timeout.connect:PT5S}") Duration connectTimeout,
            @Value("${payment.stripe.timeout.read:PT15S}") Duration readTimeout,
            @Value("${payment.stripe.max-network-retries:2}") int maxNetworkRetries,
            @Value("${payment.stripe.payment-method-types:card}") List<String> paymentMethodTypes) {
        return new StripePaymentProviderAdapter(
                gateway, objectMapper, connectTimeout, readTimeout, maxNetworkRetries,
                paymentMethodTypes);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.stripe.secret-key")
    ProviderCurrentFactPort stripeProviderCurrentFactPort(
            StripeGateway gateway,
            ObjectMapper objectMapper,
            @Value("${payment.stripe.timeout.connect:PT5S}") Duration connectTimeout,
            @Value("${payment.stripe.timeout.read:PT15S}") Duration readTimeout,
            @Value("${payment.stripe.max-network-retries:2}") int maxNetworkRetries) {
        return new StripeProviderCurrentFactAdapter(
                gateway, objectMapper, connectTimeout, readTimeout, maxNetworkRetries);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.stripe.secret-key")
    ProviderBalancePort stripeProviderBalancePort(
            StripeGateway gateway,
            @Value("${payment.stripe.timeout.connect:PT5S}") Duration connectTimeout,
            @Value("${payment.stripe.timeout.read:PT15S}") Duration readTimeout,
            @Value("${payment.stripe.max-network-retries:2}") int maxNetworkRetries) {
        return new StripeProviderBalanceAdapter(
                gateway, connectTimeout, readTimeout, maxNetworkRetries);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.stripe.webhook-secrets")
    WebhookVerificationPort stripeWebhookVerifier(
            @Value("${payment.stripe.webhook-secrets}") String configuredSecrets,
            @Value("${payment.stripe.webhook-window-seconds:300}") long toleranceSeconds) {
        List<String> secrets = Arrays.stream(configuredSecrets.split(",", -1))
                .map(String::trim).toList();
        return new StripeWebhookVerifier(secrets, Duration.ofSeconds(toleranceSeconds));
    }
}
