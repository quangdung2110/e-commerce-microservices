package com.platform.productservice.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.common.constants.ServiceConstants;
import com.platform.productservice.infrastructure.kafka.deserializer.CloudEvent;
import com.platform.productservice.worker.common.EventIdempotencyGuard;
import com.platform.productservice.worker.common.KafkaConsumerErrorHandler;
import com.platform.productservice.worker.processor.MarkdownPriceCalculatorProcessor;
import com.platform.productservice.worker.processor.MarkdownStartGateProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Kafka consumer for markdown promotion events.
 * Processes MARKDOWN_CREATED, MARKDOWN_UPDATED, MARKDOWN_ENDED events
 * from the t.promotion.markdown.events topic.
 *
 * <p>If the event start_time is in the future, the event is delegated to
 * MarkdownStartGateProcessor for delayed processing. Otherwise, it is
 * processed immediately by MarkdownPriceCalculatorProcessor.</p>
 */
@Service
public class MarkdownWorker {

    private static final Logger log = LoggerFactory.getLogger(MarkdownWorker.class);

    private static final String EVENT_MARKDOWN_CREATED = "MARKDOWN_CREATED";
    private static final String EVENT_MARKDOWN_UPDATED = "MARKDOWN_UPDATED";
    private static final String EVENT_MARKDOWN_ENDED = "MARKDOWN_ENDED";
    private static final String SCOPE_TYPE_CATEGORY = "CATEGORY";
    private static final String SCOPE_TYPE_PRODUCT = "PRODUCT";

    private final ObjectMapper objectMapper;
    private final MarkdownPriceCalculatorProcessor priceCalculatorProcessor;
    private final MarkdownStartGateProcessor startGateProcessor;
    private final EventIdempotencyGuard idempotencyGuard;
    private final KafkaConsumerErrorHandler errorHandler;

    public MarkdownWorker(
            ObjectMapper objectMapper,
            MarkdownPriceCalculatorProcessor priceCalculatorProcessor,
            MarkdownStartGateProcessor startGateProcessor,
            EventIdempotencyGuard idempotencyGuard,
            KafkaConsumerErrorHandler errorHandler) {
        this.objectMapper = objectMapper;
        this.priceCalculatorProcessor = priceCalculatorProcessor;
        this.startGateProcessor = startGateProcessor;
        this.idempotencyGuard = idempotencyGuard;
        this.errorHandler = errorHandler;
    }

    @KafkaListener(
            topics = ServiceConstants.TOPIC_MARKDOWN_EVENTS,
            groupId = ServiceConstants.CG_MARKDOWN,
            containerFactory = "markdownListenerContainerFactory"
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        log.debug("MarkdownWorker: received record [topic={}, partition={}, offset={}]",
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
            log.info("MarkdownWorker: duplicate event skipped, eventId={}, eventType={}", eventId, eventType);
            acknowledgment.acknowledge();
            return;
        }

        try {
            processEvent(event);
            acknowledgment.acknowledge();
            log.debug("MarkdownWorker: offset committed [topic={}, partition={}, offset={}, eventId={}]",
                    record.topic(), record.partition(), record.offset(), eventId);
        } catch (Exception e) {
            log.error("MarkdownWorker: processing failed for eventId={}, eventType={}: {}",
                    eventId, eventType, e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private void processEvent(CloudEvent event) {
        String eventType = event.getEventType();
        Map<String, Object> payload = event.getPayload();

        if (payload == null) {
            log.warn("MarkdownWorker: null payload for eventId={}, eventType={}",
                    event.getEventId(), eventType);
            return;
        }

        switch (eventType) {
            case EVENT_MARKDOWN_CREATED -> {
                log.info("MarkdownWorker: routing MARKDOWN_CREATED eventId={}", event.getEventId());
                handleMarkdownCreated(event, payload);
            }
            case EVENT_MARKDOWN_UPDATED -> {
                log.info("MarkdownWorker: routing MARKDOWN_UPDATED eventId={}", event.getEventId());
                handleMarkdownUpdated(event, payload);
            }
            case EVENT_MARKDOWN_ENDED -> {
                log.info("MarkdownWorker: routing MARKDOWN_ENDED eventId={}", event.getEventId());
                handleMarkdownEnded(event, payload);
            }
            default -> {
                log.info("MarkdownWorker: no handler for eventType={}, eventId={} — skipped",
                        eventType, event.getEventId());
            }
        }
    }

    private void handleMarkdownCreated(CloudEvent event, Map<String, Object> payload) {
        String startTimeStr = (String) payload.get("start_time");
        boolean isStartTimeInFuture = isStartTimeInFuture(startTimeStr);

        if (isStartTimeInFuture) {
            log.info("MarkdownWorker: start_time in future, delegating to start gate, eventId={}",
                    event.getEventId());
            startGateProcessor.scheduleForLater(event);
        } else {
            priceCalculatorProcessor.processCreated(event);
        }
    }

    private void handleMarkdownUpdated(CloudEvent event, Map<String, Object> payload) {
        String startTimeStr = (String) payload.get("start_time");
        boolean isStartTimeInFuture = isStartTimeInFuture(startTimeStr);

        if (isStartTimeInFuture) {
            log.info("MarkdownWorker: start_time in future for updated event, delegating to start gate, eventId={}",
                    event.getEventId());
            startGateProcessor.scheduleForLater(event);
        } else {
            priceCalculatorProcessor.processUpdated(event);
        }
    }

    private void handleMarkdownEnded(CloudEvent event, Map<String, Object> payload) {
        priceCalculatorProcessor.processEnded(event);
    }

    private boolean isStartTimeInFuture(String startTimeStr) {
        if (startTimeStr == null || startTimeStr.isBlank()) {
            return false;
        }
        try {
            Instant startTime = Instant.parse(startTimeStr);
            return Instant.now().isBefore(startTime);
        } catch (Exception e) {
            log.warn("MarkdownWorker: failed to parse start_time '{}': {}", startTimeStr, e.getMessage());
            return false;
        }
    }
}
