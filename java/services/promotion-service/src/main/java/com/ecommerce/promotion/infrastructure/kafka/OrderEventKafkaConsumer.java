package com.ecommerce.promotion.infrastructure.kafka;

import com.ecommerce.promotion.application.dto.OrderCancelledEvent;
import com.ecommerce.promotion.application.dto.OrderPaidEvent;
import com.ecommerce.promotion.application.dto.OrderRefundedEvent;
import com.ecommerce.promotion.application.service.OrderEventProcessingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Kafka Consumer — Inbound Order Events.
 * Theo thiết kế promotion_events.md - Section 3: Inbound Events.
 *
 * Topic: t.order.events
 * Consumer Group: promo-order-consumer
 * Ack Mode: MANUAL (spring.kafka.listener.ack-mode=manual)
 *
 * 3 event types dispatched:
 * - ORDER_PAID      → OrderEventProcessingService.processOrderPaid()
 * - ORDER_CANCELLED → OrderEventProcessingService.processOrderCancelled()
 * - ORDER_REFUNDED  → OrderEventProcessingService.processOrderRefunded()
 *
 * Idempotency Vòng 1 (Redis SETNX) đã built-in trong Lua Scripts.
 * Idempotency Vòng 2 (DB) handled bởi BudgetService.
 */
@Component
public class OrderEventKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventKafkaConsumer.class);

    private final OrderEventProcessingService orderEventProcessingService;
    private final ObjectMapper objectMapper;

    public OrderEventKafkaConsumer(OrderEventProcessingService orderEventProcessingService,
                                   ObjectMapper objectMapper) {
        this.orderEventProcessingService = orderEventProcessingService;
        this.objectMapper = objectMapper;
    }

    /**
     * @KafkaListener — lắng nghe topic t.order.events.
     * Đọc header "event_type" để dispatch đúng handler.
     * Theo thiết kế promotion_events.md - Section 3: Partition Key = order_id.
     */
    @KafkaListener(
            topics = "${kafka.topic.order-events:t.order.events}",
            groupId = "${spring.kafka.consumer.group-id:promo-order-consumer}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String eventType = extractEventType(record);
        log.info("Received order event: topic={}, partition={}, offset={}, eventType={}, key={}",
                record.topic(), record.partition(), record.offset(), eventType, record.key());

        try {
            switch (eventType) {
                case "ORDER_PAID" -> {
                    OrderPaidEvent event = objectMapper.readValue(record.value(), OrderPaidEvent.class);
                    orderEventProcessingService.processOrderPaid(event);
                }
                case "ORDER_CANCELLED" -> {
                    OrderCancelledEvent event = objectMapper.readValue(record.value(), OrderCancelledEvent.class);
                    orderEventProcessingService.processOrderCancelled(event);
                }
                case "ORDER_REFUNDED" -> {
                    OrderRefundedEvent event = objectMapper.readValue(record.value(), OrderRefundedEvent.class);
                    orderEventProcessingService.processOrderRefunded(event);
                }
                default -> log.warn("Unknown order event type: {}, ignoring", eventType);
            }

            // Manual ACK — chỉ commit offset khi xử lý thành công
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process order event: eventType={}, key={}, error={}",
                    eventType, record.key(), e.getMessage(), e);
            // KHÔNG ack → Kafka sẽ redeliver (at-least-once guarantee)
            // Idempotency kép (Redis + DB) sẽ chặn duplicate processing
            throw new RuntimeException("Failed to process order event", e);
        }
    }

    /**
     * Extract event_type từ Kafka Header.
     * Theo thiết kế promotion_events.md - Section 4: Headers include event_type.
     */
    private String extractEventType(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("event_type");
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        // Fallback: log warning
        log.warn("Missing event_type header, offset={}", record.offset());
        return "UNKNOWN";
    }
}
