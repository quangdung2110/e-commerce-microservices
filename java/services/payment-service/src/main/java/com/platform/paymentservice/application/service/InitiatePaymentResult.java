package com.platform.paymentservice.application.service;

import com.platform.paymentservice.domain.PaymentStatus;

import java.time.Instant;

public sealed interface InitiatePaymentResult {
    final class Pending implements InitiatePaymentResult {
        private final String transactionId;
        private final int paymentAttemptNumber;
        private final char[] clientSecret;
        private final String publishableKey;
        private final Instant expiresAt;

        public Pending(String transactionId, int paymentAttemptNumber, char[] clientSecret,
                       String publishableKey, Instant expiresAt) {
            this.transactionId = transactionId;
            this.paymentAttemptNumber = paymentAttemptNumber;
            this.clientSecret = clientSecret.clone();
            this.publishableKey = publishableKey;
            this.expiresAt = expiresAt;
        }

        public String transactionId() { return transactionId; }
        public int paymentAttemptNumber() { return paymentAttemptNumber; }
        public char[] clientSecret() { return clientSecret.clone(); }
        public String publishableKey() { return publishableKey; }
        public Instant expiresAt() { return expiresAt; }

        @Override public String toString() {
            return "Pending[transactionId=" + transactionId
                    + ",paymentAttemptNumber=" + paymentAttemptNumber
                    + ",clientSecret=REDACTED,publishableKey=" + publishableKey
                    + ",expiresAt=" + expiresAt + "]";
        }
    }

    record WithoutClientData(String transactionId, int paymentAttemptNumber, PaymentStatus status)
            implements InitiatePaymentResult {}

    record RequestInProgress() implements InitiatePaymentResult {}
    record IdempotencyKeyReused() implements InitiatePaymentResult {}
    record AttemptAlreadyExists() implements InitiatePaymentResult {}
    record InvalidAttemptSequence() implements InitiatePaymentResult {}
    record ProviderOutcomeUnknown() implements InitiatePaymentResult {}
    record ProviderRejected() implements InitiatePaymentResult {}
    record ReviewRequired() implements InitiatePaymentResult {}
    record Fenced() implements InitiatePaymentResult {}
    record DependencyUnavailable() implements InitiatePaymentResult {}
}
