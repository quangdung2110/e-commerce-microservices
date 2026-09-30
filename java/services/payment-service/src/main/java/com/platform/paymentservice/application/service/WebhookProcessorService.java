package com.platform.paymentservice.application.service;

import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.EncryptedSecret;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.SecretCipherPort;
import com.platform.paymentservice.application.port.out.WebhookEventHandlerPort;
import com.platform.paymentservice.application.port.out.WebhookProcessingPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class WebhookProcessorService {
    private final WebhookProcessingPort inbox;
    private final WebhookEventHandlerPort handler;
    private final SecretCipherPort cipher;
    private final IdGeneratorPort ids;
    private final ClockPort clock;
    private final Duration leaseTtl;
    private final Duration retryBaseDelay;
    private final int maximumAttempts;

    public WebhookProcessorService(WebhookProcessingPort inbox,
                                   WebhookEventHandlerPort handler,
                                   SecretCipherPort cipher,
                                   IdGeneratorPort ids,
                                   ClockPort clock,
                                   Duration leaseTtl,
                                   Duration retryBaseDelay,
                                   int maximumAttempts) {
        this.inbox = Objects.requireNonNull(inbox);
        this.handler = Objects.requireNonNull(handler);
        this.cipher = Objects.requireNonNull(cipher);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        if (leaseTtl.isNegative() || leaseTtl.isZero()) throw new IllegalArgumentException("lease TTL must be positive");
        if (retryBaseDelay.isNegative() || retryBaseDelay.isZero()) throw new IllegalArgumentException("retry delay must be positive");
        if (maximumAttempts < 1) throw new IllegalArgumentException("maximum attempts must be positive");
        this.leaseTtl = leaseTtl;
        this.retryBaseDelay = retryBaseDelay;
        this.maximumAttempts = maximumAttempts;
    }

    public WebhookProcessResult process(VerifiedWebhookNotification notification, String owner) {
        Objects.requireNonNull(notification);
        WebhookProcessingPort.ClaimResult claim = inbox.claim(
                notification.stripeEventId(), owner, clock.now(), leaseTtl);
        if (claim instanceof WebhookProcessingPort.Terminal) return WebhookProcessResult.ALREADY_TERMINAL;
        if (claim instanceof WebhookProcessingPort.Busy) return WebhookProcessResult.OWNED_BY_ANOTHER_WORKER;
        if (claim instanceof WebhookProcessingPort.Missing) {
            throw new InboxUnavailableException("verified webhook inbox row is missing");
        }
        return processClaim((WebhookProcessingPort.Claimed) claim, notification);
    }

    public List<WebhookProcessingPort.Claimed> claimDue(String owner, int limit) {
        return inbox.claimDue(owner, clock.now(), leaseTtl, limit);
    }

    public WebhookProcessResult processClaim(WebhookProcessingPort.Claimed claim) {
        return processClaim(claim, null);
    }

    private WebhookProcessResult processClaim(WebhookProcessingPort.Claimed claim,
                                              VerifiedWebhookNotification notification) {
        char[] encoded = null;
        byte[] exactBody = null;
        try {
            encoded = cipher.decrypt(new EncryptedSecret(
                    claim.encryptedRawBody().ciphertext(), claim.encryptedRawBody().keyId()),
                    "webhook:" + claim.stripeEventId() + ":raw-body");
            exactBody = Base64.getDecoder().decode(new String(encoded).getBytes(StandardCharsets.US_ASCII));
            validateIntegrity(claim, notification, exactBody);
            WebhookEventHandlerPort.HandlingResult result = handler.handle(claim, exactBody, clock.now());
            return result == WebhookEventHandlerPort.HandlingResult.COMMITTED
                    ? WebhookProcessResult.PROCESSED : WebhookProcessResult.FENCED;
        } catch (WebhookEventHandlerPort.ProcessingException known) {
            return fail(claim, known.getMessage(), known.retryable());
        } catch (IllegalArgumentException integrityOrBase64) {
            return fail(claim, "WEBHOOK_INTEGRITY_FAILURE", false);
        } catch (RuntimeException transientFailure) {
            return fail(claim, transientFailure.getClass().getSimpleName(), true);
        } finally {
            if (encoded != null) Arrays.fill(encoded, '\0');
            if (exactBody != null) Arrays.fill(exactBody, (byte) 0);
        }
    }

    private void validateIntegrity(WebhookProcessingPort.Claimed claim,
                                   VerifiedWebhookNotification notification,
                                   byte[] exactBody) {
        String digest = sha256(exactBody);
        if (!MessageDigest.isEqual(digest.getBytes(StandardCharsets.US_ASCII),
                claim.rawBodySha256().getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("stored webhook body hash mismatch");
        }
        if (notification == null) return;
        if (!claim.eventType().equals(notification.eventType())
                || !claim.eventCreatedAt().equals(notification.eventCreatedAt())
                || !claim.rawBodySha256().equals(notification.rawBodySha256())) {
            throw new IllegalArgumentException("Kafka notification metadata mismatch");
        }
        byte[] kafkaBody = Base64.getDecoder().decode(notification.rawBodyBase64());
        try {
            if (!MessageDigest.isEqual(exactBody, kafkaBody)) {
                throw new IllegalArgumentException("Kafka body does not match durable inbox body");
            }
        } finally {
            Arrays.fill(kafkaBody, (byte) 0);
        }
    }

    private WebhookProcessResult fail(WebhookProcessingPort.Claimed claim,
                                      String error, boolean retryable) {
        Instant failedAt = clock.now();
        int maxForThisFailure = retryable ? maximumAttempts : claim.processingAttempts();
        long multiplier = 1L << Math.min(Math.max(claim.processingAttempts() - 1, 0), 10);
        Instant nextRetry = failedAt.plus(retryBaseDelay.multipliedBy(multiplier));
        WebhookProcessingPort.FailureDisposition disposition = inbox.recordFailure(
                claim, error, failedAt, nextRetry, maxForThisFailure, ids.nextReviewCaseId());
        return switch (disposition) {
            case RETRY_SCHEDULED -> WebhookProcessResult.RETRY_SCHEDULED;
            case DEAD -> WebhookProcessResult.DEAD;
            case FENCED -> WebhookProcessResult.FENCED;
        };
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static final class InboxUnavailableException extends RuntimeException {
        public InboxUnavailableException(String message) {
            super(message);
        }
    }
}
