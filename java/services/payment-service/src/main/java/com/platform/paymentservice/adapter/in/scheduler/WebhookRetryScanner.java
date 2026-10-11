package com.platform.paymentservice.adapter.in.scheduler;

import com.platform.paymentservice.application.service.WebhookProcessorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = {"payment.stripe.webhook-secrets", "payment.crypto.local-key",
        "payment.stripe.secret-key"})
public final class WebhookRetryScanner {
    private final WebhookProcessorService processor;
    private final String owner = "webhook-retry:" + UUID.randomUUID();
    private final int batchSize;

    public WebhookRetryScanner(WebhookProcessorService processor,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${payment.webhook.retry.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("webhook retry batch size must be between 1 and 1000");
        }
        this.processor = processor;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${payment.webhook.retry.interval:PT30S}")
    public void scan() {
        for (int i = 0; i < batchSize; i++) {
            var claimed = processor.claimDue(owner, 1);
            if (claimed.isEmpty()) break;
            processor.processClaim(claimed.getFirst());
        }
    }
}
