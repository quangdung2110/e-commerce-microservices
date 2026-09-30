package com.platform.paymentservice.application.service;

import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.PaymentClientDataPort;
import com.platform.paymentservice.application.port.out.SecretCipherPort;
import com.platform.paymentservice.domain.PaymentStatus;

import java.util.Arrays;
import java.util.Objects;

public final class PaymentClientDataService {
    private final PaymentClientDataPort data;
    private final SecretCipherPort cipher;
    private final ClockPort clock;
    private final String publishableKey;

    public PaymentClientDataService(PaymentClientDataPort data, SecretCipherPort cipher,
                                    ClockPort clock, String publishableKey) {
        this.data = Objects.requireNonNull(data);
        this.cipher = Objects.requireNonNull(cipher);
        this.clock = Objects.requireNonNull(clock);
        if (publishableKey == null || publishableKey.isBlank()) {
            throw new IllegalArgumentException("publishableKey is required");
        }
        this.publishableKey = publishableKey;
    }

    public PaymentClientDataResult get(String transactionId) {
        PaymentClientDataPort.StoredClientData stored = data.find(transactionId).orElse(null);
        if (stored == null) return new PaymentClientDataResult.NotFound();
        if (stored.status() != PaymentStatus.PENDING
                || stored.encryptedClientSecret() == null
                || stored.clientExpiresAt() == null
                || !clock.now().isBefore(stored.clientExpiresAt())) {
            return new PaymentClientDataResult.Unusable(
                    stored.transactionId(), stored.paymentAttemptNumber(), stored.status(),
                    stored.clientExpiresAt());
        }
        try {
            char[] plaintext = cipher.decrypt(
                    stored.encryptedClientSecret(), stored.transactionId());
            try {
                return new PaymentClientDataResult.Usable(
                        stored.transactionId(), stored.paymentAttemptNumber(), stored.status(),
                        plaintext, publishableKey, stored.clientExpiresAt());
            } finally {
                Arrays.fill(plaintext, '\0');
            }
        } catch (RuntimeException keyUnavailable) {
            return new PaymentClientDataResult.Unusable(
                    stored.transactionId(), stored.paymentAttemptNumber(), stored.status(),
                    stored.clientExpiresAt());
        }
    }
}
