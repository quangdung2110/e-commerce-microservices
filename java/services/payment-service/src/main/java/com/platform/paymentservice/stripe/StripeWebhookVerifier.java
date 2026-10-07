package com.platform.paymentservice.stripe;

import com.platform.paymentservice.application.port.out.VerifiedWebhook;
import com.platform.paymentservice.application.port.out.WebhookVerificationPort;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class StripeWebhookVerifier implements WebhookVerificationPort {
    private static final Pattern EVENT_ID = Pattern.compile("^evt_[A-Za-z0-9_]+$");

    private final List<SigningSecret> secrets;
    private final Duration tolerance;

    public StripeWebhookVerifier(List<String> configuredSecrets, Duration tolerance) {
        Objects.requireNonNull(configuredSecrets);
        if (configuredSecrets.isEmpty() || configuredSecrets.size() > 2) {
            throw new IllegalArgumentException("one or two Stripe webhook secrets are required");
        }
        this.secrets = configuredSecrets.stream()
                .map(String::trim)
                .map(secret -> new SigningSecret(secretId(secret), requireSecret(secret)))
                .toList();
        this.tolerance = Objects.requireNonNull(tolerance);
        if (tolerance.isZero() || tolerance.isNegative()) {
            throw new IllegalArgumentException("webhook tolerance must be positive");
        }
    }

    @Override
    public VerifiedWebhook verify(byte[] exactBody, String stripeSignature, Instant receivedAt)
            throws InvalidWebhookException {
        Objects.requireNonNull(exactBody);
        Objects.requireNonNull(receivedAt);
        final String payload;
        try {
            payload = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(exactBody)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new InvalidWebhookException("Stripe webhook body is not valid UTF-8", invalidUtf8);
        }

        Throwable lastFailure = null;
        Clock verificationClock = Clock.fixed(receivedAt, ZoneOffset.UTC);
        for (SigningSecret candidate : secrets) {
            try {
                Event event = Webhook.constructEvent(payload, stripeSignature, candidate.value(),
                        tolerance.toSeconds(), verificationClock);
                validateEnvelope(event);
                return new VerifiedWebhook(
                        event.getId(), event.getType(), Instant.ofEpochSecond(event.getCreated()),
                        event.getApiVersion(), Boolean.TRUE.equals(event.getLivemode()), candidate.id());
            } catch (SignatureVerificationException | RuntimeException invalid) {
                lastFailure = invalid;
            }
        }
        throw new InvalidWebhookException("Stripe webhook signature or envelope is invalid", lastFailure);
    }

    private static void validateEnvelope(Event event) {
        if (event == null || event.getId() == null || !EVENT_ID.matcher(event.getId()).matches()
                || event.getId().length() > 120 || event.getType() == null
                || event.getType().isBlank() || event.getType().length() > 100
                || event.getCreated() == null
                || (event.getApiVersion() != null && event.getApiVersion().length() > 20)
                || event.getLivemode() == null) {
            throw new IllegalArgumentException("Stripe webhook envelope is incomplete");
        }
    }

    private static String requireSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Stripe webhook secret must not be blank");
        }
        return secret;
    }

    private static String secretId(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(requireSecret(secret).getBytes(StandardCharsets.UTF_8));
            return "stripe-webhook-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record SigningSecret(String id, String value) {}
}
