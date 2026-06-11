package com.platform.productservice.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.common.constants.ServiceConstants;
import com.platform.productservice.infrastructure.kafka.deserializer.CloudEvent;
import com.platform.productservice.worker.common.EventIdempotencyGuard;
import com.platform.productservice.worker.common.KafkaConsumerErrorHandler;
import com.platform.productservice.worker.consumer.handler.CategoryDeletedHandler;
import com.platform.productservice.worker.consumer.handler.SkuCreatedHandler;
import com.platform.productservice.worker.consumer.handler.SkuUpdatedHandler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * Kafka consumer for product events (t.product.events).
 * Routes events to appropriate handlers based on event type.
 * Events processed: CATEGORY_DELETED, SKU_CREATED, SKU_UPDATED.
 *
 * <p>Offset is committed only after successful processing.
 * Failed events are retried 3 times before going to DLQ.</p>
 */
@Service
public class ProductEventHandlerWorker {

    private static final Logger log = LoggerFactory.getLogger(ProductEventHandlerWorker.class);

    private final ObjectMapper objectMapper;
    private final CategoryDeletedHandler categoryDeletedHandler;
    private final SkuCreatedHandler skuCreatedHandler;
    private final SkuUpdatedHandler skuUpdatedHandler;
    private final EventIdempotencyGuard idempotencyGuard;
    private final KafkaConsumerErrorHandler errorHandler;

    public ProductEventHandlerWorker(
            ObjectMapper objectMapper,
            CategoryDeletedHandler categoryDeletedHandler,
            SkuCreatedHandler skuCreatedHandler,
            SkuUpdatedHandler skuUpdatedHandler,
            EventIdempotencyGuard idempotencyGuard,
            KafkaConsumerErrorHandler errorHandler) {
        this.objectMapper = objectMapper;
        this.categoryDeletedHandler = categoryDeletedHandler;
        this.skuCreatedHandler = skuCreatedHandler;
        this.skuUpdatedHandler = skuUpdatedHandler;
        this.idempotencyGuard = idempotencyGuard;
        this.errorHandler = errorHandler;
    }

    @KafkaListener(
            topics = ServiceConstants.TOPIC_PRODUCT_EVENTS,
            groupId = ServiceConstants.CG_EVENT_HANDLER,
            containerFactory = "productEventHandlerListenerContainerFactory"
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        log.debug("ProductEventHandlerWorker: received record [topic={}, partition={}, offset={}]",
                record.topic(), record.partition(), record.offset());

        CloudEvent event;
        try {
            event = objectMapper.readValue(record.value(), CloudEvent.class);
        } catch (Exception e) {
            errorHandler.logError(record, "unknown", "Failed to deserialize CloudEvent: " + e.getMessage());
            acknowledgment.acknowledge();
            return;
        }

        String eventId = event.getEventId() != null ? event.getEventId() : "unknown";
        String eventType = event.getEventType();

        if (!idempotencyGuard.isNew(eventId)) {
            log.info("ProductEventHandlerWorker: duplicate event skipped, eventId={}, eventType={}",
                    eventId, eventType);
            acknowledgment.acknowledge();
            return;
        }

        processEvent(event);
        acknowledgment.acknowledge();
        log.debug("ProductEventHandlerWorker: offset committed [topic={}, partition={}, offset={}, eventId={}]",
                record.topic(), record.partition(), record.offset(), eventId);
    }

    private void processEvent(CloudEvent event) {
        String eventType = event.getEventType();

        switch (eventType) {
            case "CATEGORY_DELETED":
                log.info("ProductEventHandlerWorker: routing CATEGORY_DELETED eventId={}", event.getEventId());
                categoryDeletedHandler.handle(event);
                break;
            case "SKU_CREATED":
                log.info("ProductEventHandlerWorker: routing SKU_CREATED eventId={}", event.getEventId());
                skuCreatedHandler.handle(event);
                break;
            case "SKU_UPDATED":
                log.info("ProductEventHandlerWorker: routing SKU_UPDATED eventId={}", event.getEventId());
                skuUpdatedHandler.handle(event);
                break;
            default:
                log.info("ProductEventHandlerWorker: no handler for eventType={}, eventId={} — skipped",
                        eventType, event.getEventId());
                break;
        }
    }
}
