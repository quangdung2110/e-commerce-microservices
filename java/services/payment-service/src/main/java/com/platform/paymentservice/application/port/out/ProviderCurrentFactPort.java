package com.platform.paymentservice.application.port.out;

/**
 * Reads the provider's current object when two different webhook events share the
 * same provider timestamp.  The application must not choose a winner by event ID.
 */
public interface ProviderCurrentFactPort {
    LookupResult retrievePaymentIntent(StripeWebhookEvent source, String paymentIntentId);

    LookupResult retrieveRefund(StripeWebhookEvent source, String providerRefundId);

    LookupResult retrieveDispute(StripeWebhookEvent source, String providerDisputeId);

    sealed interface LookupResult permits CurrentFact, NoTransition, ReviewRequired,
            TemporarilyUnavailable {
    }

    record CurrentFact(StripeWebhookEvent event) implements LookupResult {
        public CurrentFact {
            if (event == null) throw new IllegalArgumentException("event is required");
        }
    }

    record NoTransition(String reason, String providerStatus, String evidenceJson) implements LookupResult {
        public NoTransition {
            if (reason == null || reason.isBlank())
                throw new IllegalArgumentException("reason is required");
        }
    }

    record ReviewRequired(String reasonCode, String evidenceJson) implements LookupResult {
        public ReviewRequired {
            if (reasonCode == null || reasonCode.isBlank())
                throw new IllegalArgumentException("reasonCode is required");
        }
    }

    record TemporarilyUnavailable(String errorCode) implements LookupResult {
        public TemporarilyUnavailable {
            if (errorCode == null || errorCode.isBlank())
                throw new IllegalArgumentException("errorCode is required");
        }
    }
}
