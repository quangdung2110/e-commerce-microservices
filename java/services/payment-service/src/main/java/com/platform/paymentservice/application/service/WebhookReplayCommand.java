package com.platform.paymentservice.application.service;

import java.util.Objects;

public record WebhookReplayCommand(String stripeEventId, String actor, String approver,
                                   String correlationId, String idempotencyKey,
                                   String causeRef) {
    public WebhookReplayCommand {
        Objects.requireNonNull(stripeEventId);
        Objects.requireNonNull(actor);
        Objects.requireNonNull(approver);
        Objects.requireNonNull(correlationId);
        Objects.requireNonNull(idempotencyKey);
        Objects.requireNonNull(causeRef);
        if (!stripeEventId.matches("^evt_[A-Za-z0-9_]+$") || actor.length() < 3
                || actor.length() > 128 || approver.length() < 3 || approver.length() > 128
                || actor.equals(approver) || correlationId.isBlank() || correlationId.length() > 128
                || idempotencyKey.length() < 16 || idempotencyKey.length() > 255
                || causeRef.isBlank() || causeRef.length() > 255) {
            throw new IllegalArgumentException("invalid webhook replay command");
        }
    }
}
