package com.example.common.consumer;

import com.example.common.event.BaseEvent;

/**
 * Interface chung cho việc consume events
 * Có thể implement cho Kafka, RabbitMQ, hoặc bất kỳ message broker nào
 */
public interface EventConsumer<T extends BaseEvent> {
    
    /**
     * Consume một event từ message broker
     * @param event Event cần xử lý
     */
    void consume(T event);
    
    /**
     * Xử lý lỗi khi consume event thất bại
     * @param event Event gây lỗi
     * @param exception Exception xảy ra
     */
    void onError(T event, Exception exception);
}
