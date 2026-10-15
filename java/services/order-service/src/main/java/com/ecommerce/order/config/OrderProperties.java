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
        Fulfillment fulfillment,
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

    /**
     * {@code readTimeout} is the default; the three per-provider values override it. Each must stay at
     * least one second under the mesh timeout of the routes Order calls (mesh runtime contract section 7,
     * Order ADR-023).
     */
    public record Clients(String serviceName, String inventoryBaseUrl, String promotionBaseUrl,
                          String paymentBaseUrl, Duration connectTimeout, Duration readTimeout,
                          Duration inventoryReadTimeout, Duration promotionReadTimeout,
                          Duration paymentReadTimeout, Integer maxConcurrent) {

        /** Calls in flight to one provider at once; more are not sent (Order ADR-024). */
        public int maxConcurrentCalls() {
            return maxConcurrent != null ? maxConcurrent : 64;
        }

        public Duration inventoryRead() {
            return inventoryReadTimeout != null ? inventoryReadTimeout : readTimeout;
        }

        public Duration promotionRead() {
            return promotionReadTimeout != null ? promotionReadTimeout : readTimeout;
        }

        public Duration paymentRead() {
            return paymentReadTimeout != null ? paymentReadTimeout : readTimeout;
        }
    }

    public record Fulfillment(Duration deliveryConfirmationOverdue, Duration deliveredAtFutureSkew) {
    }

    public record Kafka(boolean consumersEnabled, String paymentTopic, String inventoryTopic,
                        String orderTopic, String notificationTopic, String paymentGroup,
                        String inventoryShipGroup, String inventoryReturnGroup, String receiptGroup) {
    }

    public record Workers(boolean enabled, String instanceId, Duration dispatchInterval, int dispatchBatchSize,
                          Duration lease, Duration reconcileInterval) {
    }

    public record Outbox(Duration retention, Duration receiptSafetyDelay, String trimCron) {
    }

    public record RedisMirror(boolean enabled, Duration ttl) {
    }
}
