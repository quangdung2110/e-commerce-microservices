package com.platform.paymentservice.application.service;

public record WebhookReplayResult(Outcome outcome, int statusCode) {
    public enum Outcome {
        SCHEDULED, MISSING, PAYLOAD_SHREDDED, BUSY,
        REQUEST_IN_PROGRESS, IDEMPOTENCY_KEY_REUSED, FENCED
    }
}
