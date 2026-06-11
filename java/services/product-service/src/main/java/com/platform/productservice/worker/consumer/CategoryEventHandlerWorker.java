package com.platform.productservice.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.common.constants.ServiceConstants;
import com.platform.productservice.domain.enums.ProductEventType;
import com.platform.productservice.infrastructure.kafka.deserializer.CloudEvent;
import com.platform.productservice.worker.common.EventIdempotencyGuard;
import com.platform.productservice.worker.common.KafkaConsumerErrorHandler;
import com.platform.productservice.worker.consumer.handler.CategoryDeletedHandler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * Kafka consumer for category events (t.category.events).
 * Handles CATEGORY_DELETED events only. All other category events are logged and skipped.
 *
 * <p>Offset is committed only after successful processing.
 * Failed events are retried 3 times before going to DLQ.</p>
 */
@Service
public class CategoryEventHandlerWorker {

    private static final Logger log = LoggerFactory.getLogger(CategoryEventHandlerWorker.class);

    private final ObjectMapper objectMapper;
    private final CategoryDeletedHandler categoryDeletedHandler;
    private final EventIdempotencyGuard idempotencyGuard;
    private final KafkaConsumerErrorHandler errorHandler;

    public CategoryEventHandlerWorker(
            ObjectMapper objectMapper,
            CategoryDeletedHandler categoryDeletedHandler,
            EventIdempotencyGuard idempotencyGuard,
            KafkaConsumerErrorHandler errorHandler) {
        this.objectMapper = objectMapper;
        this.categoryDeletedHandler = categoryDeletedHandler;
        this.idempotencyGuard = idempotencyGuard;
        this.errorHandler = errorHandler;
    }

    @KafkaListener(
            topics = ServiceConstants.TOPIC_CATEGORY_EVENTS,
            groupId = ServiceConstants.CG_EVENT_HANDLER,
            containerFactory = "productEventHandlerListenerContainerFactory"
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        log.debug("CategoryEventHandlerWorker: received record [topic={}, partition={}, offset={}]",
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
            log.info("CategoryEventHandlerWorker: duplicate event skipped, eventId={}, eventType={}",
                    eventId, eventType);
            acknowledgment.acknowledge();
            return;
        }

        if (ProductEventType.CATEGORY_DELETED.name().equals(eventType)) {
            log.info("CategoryEventHandlerWorker: routing CATEGORY_DELETED eventId={}", eventId);
            categoryDeletedHandler.handle(event);
        } else {
            log.info("CategoryEventHandlerWorker: no action needed for eventType={}, eventId={} — skipped",
                    eventType, eventId);
        }

        acknowledgment.acknowledge();
        log.debug("CategoryEventHandlerWorker: offset committed [topic={}, partition={}, offset={}, eventId={}]",
                record.topic(), record.partition(), record.offset(), eventId);
    }
}
