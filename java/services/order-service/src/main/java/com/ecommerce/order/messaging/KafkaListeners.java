package com.ecommerce.order.messaging;

import com.ecommerce.order.common.Json;
import com.ecommerce.order.store.OutboxStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumer groups of operations.md §8. Each listener ACKs only after the durable inbox
 * transaction committed; any exception leaves the record for redelivery.
 */
@Component
@ConditionalOnProperty(prefix = "order.kafka", name = "consumers-enabled", havingValue = "true", matchIfMissing = true)
public class KafkaListeners {

    private final PaymentIntake payments;
    private final FulfillmentIntake fulfillment;
    private final InspectionIntake inspections;
    private final OutboxStore outbox;
    private final Json json;

    public KafkaListeners(PaymentIntake payments, FulfillmentIntake fulfillment, InspectionIntake inspections,
                          OutboxStore outbox, Json json) {
        this.payments = payments;
        this.fulfillment = fulfillment;
        this.inspections = inspections;
        this.outbox = outbox;
        this.json = json;
    }

    @KafkaListener(topics = "${order.kafka.payment-topic}", groupId = "${order.kafka.payment-group}")
    public void payment(ConsumerRecord<String, String> record, Acknowledgment ack) {
        payments.handle(record.key(), record.value());
        ack.acknowledge();
    }

    @KafkaListener(topics = "${order.kafka.inventory-topic}", groupId = "${order.kafka.inventory-ship-group}")
    public void inventoryDispatch(ConsumerRecord<String, String> record, Acknowledgment ack) {
        fulfillment.handleDispatch(record.key(), record.value());
        ack.acknowledge();
    }

    @KafkaListener(topics = "${order.kafka.inventory-topic}", groupId = "${order.kafka.inventory-return-group}")
    public void inventoryReturn(ConsumerRecord<String, String> record, Acknowledgment ack) {
        inspections.handle(record.key(), record.value());
        ack.acknowledge();
    }

    /** Receipt observer (ADR-016): exact (event_id, destination) proof used by the trimmer. */
    @KafkaListener(topics = {"${order.kafka.order-topic}", "${order.kafka.notification-topic}"},
            groupId = "${order.kafka.receipt-group}")
    public void receipts(ConsumerRecord<String, String> record, Acknowledgment ack) {
        JsonNode event = parse(record.value());
        if (event != null && "/services/order".equals(event.path("source").asText())) {
            try {
                UUID eventId = UUID.fromString(event.path("id").asText());
                outbox.recordReceipt(eventId, record.topic(), record.partition(), record.offset());
            } catch (IllegalArgumentException ignored) {
                // Not an Order outbox identity; nothing to record.
            }
        }
        ack.acknowledge();
    }

    private JsonNode parse(String value) {
        try {
            return value == null ? null : json.read(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
