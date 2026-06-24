package com.ecommerce.search.worker;

import com.ecommerce.search.common.EventType;
import com.ecommerce.search.domain.event.CloudEventEnvelope;
import com.ecommerce.search.worker.handler.CategoryEventHandlers;
import com.ecommerce.search.worker.handler.ProductEventHandlers;
import com.ecommerce.search.worker.handler.ReviewEventHandlers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Central dispatcher for all CloudEvents consumed by the Search Service.
 *
 * <p>Routes each event to the appropriate handler class based on
 * {@link CloudEventEnvelope#eventType()}.
 *
 * <h3>Error handling</h3>
 * Exceptions thrown by individual handlers are logged with full MDC context
 * and re-thrown so that the caller ({@link ProductIndexingConsumer}) can
 * apply the Phase 22b error policy (transient → retry, non-recoverable →
 * log + skip + ACK).
 *
 * <h3>MDC context</h3>
 * Every invocation populates {@code event_type} and {@code event_id} in the
 * diagnostic context so that downstream logs are correlated with the event
 * being processed.
 *
 * @see ProductEventHandlers
 * @see CategoryEventHandlers
 * @see ReviewEventHandlers
 * @see ProductIndexingConsumer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventHandler {

    private final ProductEventHandlers productHandlers;
    private final CategoryEventHandlers categoryHandlers;
    private final ReviewEventHandlers reviewHandlers;

    /**
     * Process a single CloudEvent by dispatching to the appropriate handler.
     *
     * @param envelope the deserialized CloudEvents 1.0 envelope
     */
    public void handle(CloudEventEnvelope envelope) {
        String eventType = envelope.eventType();
        String eventId = envelope.eventId();

        // Populate MDC for correlated logging
        MDC.put("event_type", eventType);
        MDC.put("event_id", eventId);

        try {
            // === 1. Resolve EventType enum ===
            EventType type = EventType.fromString(eventType);
            if (type == null) {
                log.warn("Unknown event type '{}', skipping", eventType);
                return;
            }

            // === 2. Route to the appropriate handler ===
            switch (type) {
                // -- Product events --
                case PRODUCT_CREATED -> productHandlers.handleProductCreated(envelope);
                case PRODUCT_UPDATED -> productHandlers.handleProductUpdated(envelope);
                case SKU_UPDATED -> productHandlers.handleSkuUpdated(envelope);
                case SKU_CREATED -> productHandlers.handleSkuCreated(envelope);
                case PRODUCT_DELETED -> productHandlers.handleProductDeleted(envelope);
                case SKU_DELETED -> productHandlers.handleSkuDeleted(envelope);
                // -- Category events --
                case CATEGORY_CREATED -> categoryHandlers.handleCategoryCreated(envelope);
                case CATEGORY_UPDATED -> categoryHandlers.handleCategoryUpdated(envelope);
                case CATEGORY_DELETED -> categoryHandlers.handleCategoryDeleted(envelope);
                // -- Review events --
                case REVIEW_METRICS_UPDATED -> reviewHandlers.handleReviewMetricsUpdated(envelope);
                // -- Legacy / disallowed --
                default -> log.warn("Legacy or disallowed event type '{}', skipping", type);
            }

        } catch (Exception e) {
            log.error("Failed to handle event: type={}, id={}, source={}",
                eventType, eventId, envelope.source(), e);
            throw e;
        } finally {
            MDC.remove("event_type");
            MDC.remove("event_id");
        }
    }
}
