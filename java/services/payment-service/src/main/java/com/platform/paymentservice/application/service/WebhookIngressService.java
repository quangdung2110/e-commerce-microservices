package com.platform.paymentservice.application.service;

import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.EncryptedSecret;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.SecretCipherPort;
import com.platform.paymentservice.application.port.out.VerifiedWebhook;
import com.platform.paymentservice.application.port.out.VerifiedWebhookPublisherPort;
import com.platform.paymentservice.application.port.out.WebhookInboxPort;
import com.platform.paymentservice.application.port.out.WebhookVerificationPort;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

public final class WebhookIngressService {
    private final WebhookVerificationPort verifier;
    private final WebhookInboxPort inbox;
    private final VerifiedWebhookPublisherPort publisher;
    private final SecretCipherPort cipher;
    private final IdGeneratorPort ids;
    private final ClockPort clock;

    public WebhookIngressService(WebhookVerificationPort verifier, WebhookInboxPort inbox,
                                 VerifiedWebhookPublisherPort publisher, SecretCipherPort cipher,
                                 IdGeneratorPort ids, ClockPort clock) {
        this.verifier = Objects.requireNonNull(verifier);
        this.inbox = Objects.requireNonNull(inbox);
        this.publisher = Objects.requireNonNull(publisher);
        this.cipher = Objects.requireNonNull(cipher);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
    }

    public WebhookIngressResult ingest(byte[] exactBody, String stripeSignature) {
        if (exactBody == null || exactBody.length == 0 || stripeSignature == null
                || stripeSignature.isBlank() || stripeSignature.length() > 4096) {
            return new WebhookIngressResult.Rejected();
        }
        Instant receivedAt = clock.now();
        final VerifiedWebhook webhook;
        try {
            webhook = verifier.verify(exactBody.clone(), stripeSignature, receivedAt);
        } catch (WebhookVerificationPort.InvalidWebhookException invalid) {
            return new WebhookIngressResult.Rejected();
        }

        String bodyBase64 = Base64.getEncoder().encodeToString(exactBody);
        char[] bodyForEncryption = bodyBase64.toCharArray();
        char[] signatureForEncryption = stripeSignature.toCharArray();
        try {
            Instant verifiedAt = clock.now();
            String contextPrefix = "webhook:" + webhook.stripeEventId() + ":";
            EncryptedSecret encryptedBody = cipher.encrypt(bodyForEncryption, contextPrefix + "raw-body");
            EncryptedSecret encryptedSignature = cipher.encrypt(
                    signatureForEncryption, contextPrefix + "stripe-signature");
            String sha256 = sha256(exactBody);
            WebhookInboxPort.SaveResult saved = inbox.saveVerified(new WebhookInboxPort.VerifiedDelivery(
                    webhook, encryptedBody, encryptedSignature, sha256, receivedAt, verifiedAt));
            if (saved == WebhookInboxPort.SaveResult.DUPLICATE_TERMINAL) {
                return new WebhookIngressResult.DuplicateTerminal();
            }
            publisher.publish(new VerifiedWebhookPublisherPort.Message(
                    ids.nextEventId(), verifiedAt, webhook, receivedAt, verifiedAt,
                    bodyBase64, sha256, stripeSignature));
            return new WebhookIngressResult.Accepted();
        } catch (RuntimeException dependencyFailure) {
            return new WebhookIngressResult.Unavailable();
        } finally {
            Arrays.fill(bodyForEncryption, '\0');
            Arrays.fill(signatureForEncryption, '\0');
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
