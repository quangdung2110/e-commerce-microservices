package com.platform.paymentservice.application.service;

public sealed interface WebhookIngressResult {
    record Accepted() implements WebhookIngressResult {}
    record DuplicateTerminal() implements WebhookIngressResult {}
    record Rejected() implements WebhookIngressResult {}
    record Unavailable() implements WebhookIngressResult {}
}
