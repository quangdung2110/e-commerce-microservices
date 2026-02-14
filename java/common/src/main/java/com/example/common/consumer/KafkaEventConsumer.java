package com.example.common.consumer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;

import com.example.common.event.BaseEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * Base class cho Kafka Event Consumer
 * Có thể tái sử dụng cho các service khác
 */
@Slf4j
public abstract class KafkaEventConsumer<T extends BaseEvent> implements EventConsumer<T> {
    
    private final KafkaTemplate<String, T> kafkaTemplate;
    private final String topics;
    private final String groupId;
    private final String deadLetterTopic;
    
    // Simple in-memory idempotency check (có thể thay bằng Redis/DB trong production)
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();
    private static final int MAX_PROCESSED_EVENTS = 10000;
    
    protected KafkaEventConsumer(
            KafkaTemplate<String, T> kafkaTemplate,
            String topics,
            String groupId,
            String deadLetterTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.groupId = groupId;
        this.deadLetterTopic = deadLetterTopic;
    }
    
    /**
     * KafkaListener method - được gọi tự động khi có message
     */
    @KafkaListener(
        topics = "#{__listener.topics}",
        groupId = "#{__listener.groupId}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void listen(
            @Payload T event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {
        
        log.info("Received event: eventId={}, eventType={}, topic={}, partition={}, offset={}", 
            event.getEventId(), 
            event.getEventType(), 
            topic, 
            partition, 
            offset);
        
        try {
            consume(event);
        } catch (Exception e) {
            log.error("Error processing event: eventId={}, error={}", 
                event.getEventId(), e.getMessage(), e);
            onError(event, e);
        }
    }
    
    @Override
    public void consume(T event) {
        // Idempotency check
        if (isAlreadyProcessed(event)) {
            log.warn("Event already processed (idempotency): eventId={}, eventType={}", 
                event.getEventId(), event.getEventType());
            return;
        }
        
        try {
            // Template method - subclass override
            processEvent(event);
            
            // Mark as processed
            markAsProcessed(event);
            
            log.info("Event processed successfully: eventId={}, eventType={}, aggregateId={}", 
                event.getEventId(), 
                event.getEventType(), 
                event.getAggregateId());
                
        } catch (Exception e) {
            log.error("Failed to process event: eventId={}, eventType={}, error={}", 
                event.getEventId(), 
                event.getEventType(), 
                e.getMessage(), 
                e);
            throw new RuntimeException("Event processing failed", e);
        }
    }
    
    @Override
    public void onError(T event, Exception exception) {
        log.error("Sending event to Dead Letter Topic: eventId={}, topic={}", 
            event.getEventId(), deadLetterTopic);
        
        try {
            // Send to DLT
            kafkaTemplate.send(deadLetterTopic, event.getAggregateId(), event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("Event sent to DLT successfully: eventId={}, topic={}", 
                            event.getEventId(), deadLetterTopic);
                    } else {
                        log.error("Failed to send event to DLT: eventId={}, error={}", 
                            event.getEventId(), ex.getMessage(), ex);
                    }
                });
        } catch (Exception e) {
            log.error("Critical error sending to DLT: eventId={}, error={}", 
                event.getEventId(), e.getMessage(), e);
        }
    }
    
    /**
     * Abstract method cho subclass implement business logic
     */
    protected abstract void processEvent(T event);
    
    /**
     * Check if event already processed (idempotency)
     */
    private boolean isAlreadyProcessed(T event) {
        return processedEventIds.contains(event.getEventId());
    }
    
    /**
     * Mark event as processed
     */
    private void markAsProcessed(T event) {
        // Simple implementation - có thể thay bằng Redis với TTL
        if (processedEventIds.size() >= MAX_PROCESSED_EVENTS) {
            // Clear oldest entries (simple strategy)
            processedEventIds.clear();
        }
        processedEventIds.add(event.getEventId());
    }
    
    // Getters for SpEL expressions
    public String getTopics() {
        return topics;
    }
    
    public String getGroupId() {
        return groupId;
    }
}
