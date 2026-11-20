package com.platform.paymentservice.application.port.out;

import java.util.Objects;
import java.util.Optional;

public sealed interface ProviderOperationResult {
    String providerRequestId();
    String resultJson();

    final class Confirmed implements ProviderOperationResult {
        private final String providerObjectId;
        private final String providerRequestId;
        private final String resultJson;
        private final char[] transientClientSecret;
        private final String providerStatus;
        private final String providerChargeId;
        private final Long amountMinor;
        private final String currency;

        public Confirmed(String providerObjectId, String providerRequestId, String resultJson) {
            this(providerObjectId, providerRequestId, resultJson, null);
        }

        public Confirmed(String providerObjectId, String providerRequestId, String resultJson,
                         char[] transientClientSecret) {
            this(providerObjectId, providerRequestId, resultJson, transientClientSecret, null, null);
        }

        public Confirmed(String providerObjectId, String providerRequestId, String resultJson,
                         char[] transientClientSecret, String providerStatus,
                         String providerChargeId) {
            this(providerObjectId, providerRequestId, resultJson, transientClientSecret,
                    providerStatus, providerChargeId, null, null);
        }

        public Confirmed(String providerObjectId, String providerRequestId, String resultJson,
                         char[] transientClientSecret, String providerStatus,
                         String providerChargeId, Long amountMinor, String currency) {
            this.providerObjectId = Objects.requireNonNull(providerObjectId);
            this.providerRequestId = providerRequestId;
            this.resultJson = Objects.requireNonNull(resultJson);
            this.transientClientSecret = transientClientSecret == null ? null : transientClientSecret.clone();
            this.providerStatus = providerStatus;
            this.providerChargeId = providerChargeId;
            this.amountMinor = amountMinor;
            this.currency = currency;
        }

        public String providerObjectId() { return providerObjectId; }
        @Override public String providerRequestId() { return providerRequestId; }
        @Override public String resultJson() { return resultJson; }
        public String providerStatus() { return providerStatus; }
        public String providerChargeId() { return providerChargeId; }
        public Long amountMinor() { return amountMinor; }
        public String currency() { return currency; }

        public Optional<char[]> transientClientSecret() {
            return transientClientSecret == null
                    ? Optional.empty()
                    : Optional.of(transientClientSecret.clone());
        }

        @Override
        public String toString() {
            return "Confirmed[providerObjectId=" + providerObjectId
                    + ", providerRequestId=" + providerRequestId
                    + ", resultJson=" + resultJson + ", transientClientSecret=REDACTED]";
        }
    }

    record Rejected(String providerRequestId, String resultJson, boolean noPaymentCreated) implements ProviderOperationResult {
        public Rejected { Objects.requireNonNull(resultJson); }
        // Ordinary 4xx/idempotency errors are not proof that a CREATE had no side effect.
        public Rejected(String providerRequestId, String resultJson) {
            this(providerRequestId, resultJson, false);
        }
    }

    record Unknown(String providerRequestId, String resultJson) implements ProviderOperationResult {
        public Unknown { Objects.requireNonNull(resultJson); }
    }

    record ReviewRequired(String providerRequestId, String resultJson) implements ProviderOperationResult {
        public ReviewRequired { Objects.requireNonNull(resultJson); }
    }
}
