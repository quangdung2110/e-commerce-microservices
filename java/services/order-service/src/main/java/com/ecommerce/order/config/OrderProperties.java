package com.ecommerce.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Typed {@code order.*} configuration (docs/order/operations.md §2). */
@ConfigurationProperties(prefix = "order")
public record OrderProperties(
        Identity identity,
        Checkout checkout,
        Refund refund,
        Search search,
        Notification notification,
        Temporal temporal,
        Clients clients,
        Kafka kafka,
        Workers workers,
        Outbox outbox,
        RedisMirror redisMirror) {

    public record Identity(String serviceHeader, String userIdHeader, String rolesHeader, String actorHeader,
                           String adminRole, List<String> checkoutClients, List<String> financeClients) {
    }

    public record Checkout(Duration paymentWindow, Duration clockSkewSafety, Duration submitWait) {
    }

    public record Refund(Duration inspectionWait) {
    }

    public record Search(Duration defaultWindow) {
    }

    public record Notification(String frontendBaseUrl, String paymentMethodLabel) {
    }

    public record Temporal(boolean enabled, String target, String namespace, boolean workerEnabled,
                           String checkoutQueue, String refundQueue, String reconciliationQueue,
                           Duration activityTimeout, int activityMaxAttempts) {
    }

    public record Clients(String serviceName, String inventoryBaseUrl, String promotionBaseUrl,
                          String paymentBaseUrl, Duration connectTimeout, Duration readTimeout) {
    }

    public record Kafka(boolean consumersEnabled, String paymentTopic, String inventoryTopic, String deliveryTopic,
                        String orderTopic, String notificationTopic, String paymentGroup,
                        String inventoryShipGroup, String inventoryReturnGroup, String deliveryGroup,
                        String receiptGroup) {
    }

    public record Workers(boolean enabled, String instanceId, Duration dispatchInterval, int dispatchBatchSize,
                          Duration lease, Duration reconcileInterval) {
    }

    public record Outbox(Duration retention, Duration receiptSafetyDelay, String trimCron) {
    }

    public record RedisMirror(boolean enabled, Duration ttl) {
    }
}
