package com.platform.paymentservice.application.service;

public enum WebhookProcessResult {
    PROCESSED,
    ALREADY_TERMINAL,
    OWNED_BY_ANOTHER_WORKER,
    RETRY_SCHEDULED,
    DEAD,
    FENCED
}
