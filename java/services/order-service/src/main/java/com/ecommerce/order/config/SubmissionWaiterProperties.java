package com.ecommerce.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;

import java.time.Duration;

/** Per-instance bounds for the non-durable HTTP waiters (Order ADR-027). */
@Validated
@ConfigurationProperties("order.checkout.waiter")
public record SubmissionWaiterProperties(
        @DefaultValue("512") @Min(1) int maxWaiters,
        @DefaultValue("16") @Min(1) int responseThreads,
        @DefaultValue("256") @Min(1) int batchSize,
        @DefaultValue("1s") Duration recoveryInterval,
        @DefaultValue("1s") Duration reconnectDelay) {
    public SubmissionWaiterProperties {
        if (recoveryInterval != null && recoveryInterval.toMillis() < 1
                || reconnectDelay != null && reconnectDelay.toMillis() < 1) {
            throw new IllegalArgumentException("Submission waiter intervals must be at least 1 ms");
        }
    }
}
