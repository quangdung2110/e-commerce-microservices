package com.platform.paymentservice.application.service;

import java.util.Objects;

public record DeclinePaymentCommand(
        String transactionId,
        String declineCode,
        String errorMessage,
        String outcomeType,
        String providerEventId,
        String sanitizedProviderResultJson,
        Long inboxId,
        Long inboxLeaseToken,
        java.time.Instant providerEventCreatedAt,
        String replayActor,
        String replayCauseRef) {
    public DeclinePaymentCommand(String transactionId, String declineCode,
                                 String errorMessage, String outcomeType,
                                 String providerEventId, String sanitizedProviderResultJson) {
        this(transactionId, declineCode, errorMessage, outcomeType, providerEventId,
                sanitizedProviderResultJson, null, null, null, null, null);
    }

    public DeclinePaymentCommand {
        Objects.requireNonNull(transactionId);
        Objects.requireNonNull(declineCode);
        Objects.requireNonNull(providerEventId);
        Objects.requireNonNull(sanitizedProviderResultJson);
        if (transactionId.isBlank() || declineCode.isBlank() || providerEventId.isBlank())
            throw new IllegalArgumentException("decline identifiers must not be blank");
        if (errorMessage != null && errorMessage.length() > 500)
            throw new IllegalArgumentException("decline error message is too long");
        if ((inboxId == null) != (inboxLeaseToken == null)
                || (inboxId == null) != (providerEventCreatedAt == null)) {
            throw new IllegalArgumentException("inbox decline context must be complete");
        }
        if ((replayActor == null) != (replayCauseRef == null))
            throw new IllegalArgumentException("manual replay audit context must be complete");
    }
}
