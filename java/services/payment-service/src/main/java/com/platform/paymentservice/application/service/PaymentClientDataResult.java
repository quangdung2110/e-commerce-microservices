package com.platform.paymentservice.application.service;

import com.platform.paymentservice.domain.PaymentStatus;

import java.time.Instant;

public sealed interface PaymentClientDataResult {
    final class Usable implements PaymentClientDataResult {
        private final String transactionId;
        private final int paymentAttemptNumber;
        private final PaymentStatus status;
        private final char[] clientSecret;
        private final String publishableKey;
        private final Instant clientExpiresAt;

        public Usable(String transactionId, int paymentAttemptNumber, PaymentStatus status,
                      char[] clientSecret, String publishableKey, Instant clientExpiresAt) {
            this.transactionId = transactionId;
            this.paymentAttemptNumber = paymentAttemptNumber;
            this.status = status;
            this.clientSecret = clientSecret.clone();
            this.publishableKey = publishableKey;
            this.clientExpiresAt = clientExpiresAt;
        }

        public String transactionId() { return transactionId; }
        public int paymentAttemptNumber() { return paymentAttemptNumber; }
        public PaymentStatus status() { return status; }
        public char[] clientSecret() { return clientSecret.clone(); }
        public String publishableKey() { return publishableKey; }
        public Instant clientExpiresAt() { return clientExpiresAt; }

        @Override public String toString() {
            return "Usable[transactionId=" + transactionId
                    + ",paymentAttemptNumber=" + paymentAttemptNumber
                    + ",status=" + status + ",clientSecret=REDACTED,publishableKey="
                    + publishableKey + ",clientExpiresAt=" + clientExpiresAt + "]";
        }
    }

    record Unusable(String transactionId, int paymentAttemptNumber, PaymentStatus status,
                    Instant clientExpiresAt) implements PaymentClientDataResult {}

    record NotFound() implements PaymentClientDataResult {}
}
