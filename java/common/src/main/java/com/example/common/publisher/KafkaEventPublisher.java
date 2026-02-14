package com.example.common.publisher;

import com.example.common.event.BaseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

/**
 * Implementation của EventPublisher sử dụng Kafka
 * Base class có thể tái sử dụng cho các service khác
 */
@Slf4j
public abstract class KafkaEventPublisher<T extends BaseEvent> implements EventPublisher<T> {
    
    private final KafkaTemplate<String, T> kafkaTemplate;
    private final String defaultTopic;
    
    protected KafkaEventPublisher(KafkaTemplate<String, T> kafkaTemplate, String defaultTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.defaultTopic = defaultTopic;
    }
    
    @Override
    public void publish(T event) {
        publish(event, event.getAggregateId());
    }
    
    @Override
    public void publish(T event, String routingKey) {
        event.initializeEvent();
        
        CompletableFuture<SendResult<String, T>> future = 
            kafkaTemplate.send(defaultTopic, routingKey, event);
        
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Event published successfully: eventId={}, eventType={}, topic={}, partition={}, offset={}", 
                    event.getEventId(), 
                    event.getEventType(),
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            } else {
                log.error("Failed to publish event: eventId={}, eventType={}, error={}", 
                    event.getEventId(), 
                    event.getEventType(), 
                    ex.getMessage(), ex);
            }
        });
    }
    
    protected String getDefaultTopic() {
        return defaultTopic;
    }
}
