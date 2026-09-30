package com.platform.paymentservice.domain;

import java.time.Instant;
import java.util.Objects;

public record ProviderCheckpoint(Instant eventCreatedAt, String eventId) {
    public ProviderCheckpoint { Objects.requireNonNull(eventCreatedAt); Objects.requireNonNull(eventId); }

    public CheckpointDecision decide(Instant candidateCreatedAt, String candidateEventId) {
        Objects.requireNonNull(candidateCreatedAt); Objects.requireNonNull(candidateEventId);
        int comparison = candidateCreatedAt.compareTo(eventCreatedAt);
        if (comparison < 0) return CheckpointDecision.STALE;
        if (comparison > 0) return CheckpointDecision.APPLY;
        return eventId.equals(candidateEventId)
                ? CheckpointDecision.DUPLICATE
                : CheckpointDecision.TIE_RETRIEVE_REQUIRED;
    }
}
