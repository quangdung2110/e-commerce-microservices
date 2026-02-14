package com.example.common.publisher;

import com.example.common.event.BaseEvent;

/**
 * Interface chung cho việc publish events
 * Có thể implement cho Kafka, RabbitMQ, hoặc bất kỳ message broker nào
 */
public interface EventPublisher<T extends BaseEvent> {
    
    /**
     * Publish một event lên message broker
     * @param event Event cần publish
     */
    void publish(T event);
    
    /**
     * Publish một event với routing key cụ thể
     * @param event Event cần publish
     * @param routingKey Key để route message
     */
    void publish(T event, String routingKey);
}
