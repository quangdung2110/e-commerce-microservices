package com.ecommerce.review.messaging;

import com.ecommerce.review.common.RequestContext;
import com.ecommerce.review.service.EntitlementService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * WF-REVIEW-001: consumes {@code ORDER_COMPLETED} from {@code t.order.events}.
 *
 * <p>Review dispatches on the exact CloudEvents type and acknowledges any other Order
 * message without an effect — the topic carries Order's whole lifecycle and only this
 * one fact grants an entitlement.
 */
@Component
public class OrderCompletedConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderCompletedConsumer.class);
    private static final String ORDER_COMPLETED = "com.ecommerce.order.completed.v1";

    private final InboundEvents inboundEvents;
    private final EntitlementService entitlements;

    public OrderCompletedConsumer(InboundEvents inboundEvents, EntitlementService entitlements) {
        this.inboundEvents = inboundEvents;
        this.entitlements = entitlements;
    }

    @KafkaListener(
            // Disabled in the integration suite except in the CDC test, which is the
            // only one where a live consumer is the subject rather than background
            // noise writing into tables other tests are truncating.
            autoStartup = "${review.consumers.auto-startup:true}",
            topics = "${review.topics.order}",
            groupId = "${review.consumer-groups.order}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        CloudEventEnvelope envelope = inboundEvents.parse(record.value());
        if (!ORDER_COMPLETED.equals(envelope.type())) {
            acknowledgment.acknowledge();
            return;
        }
        RequestContext.setCorrelationId(envelope.correlationid());
        try {
            UUID eventId = inboundEvents.requireEventId(envelope);
            OrderCompletedPayload payload = inboundEvents.bind(envelope, OrderCompletedPayload.class);
            validate(payload);
            entitlements.apply(eventId, payload, record.topic(), record.partition(), record.offset());
            acknowledgment.acknowledge();
        } finally {
            RequestContext.clear();
        }
    }

    /**
     * A missing product or SKU is a contract failure and goes to the dead-letter topic; a
     * missing variant name is accepted as null for historic orders (WF-REVIEW-001).
     */
    private static void validate(OrderCompletedPayload payload) {
        if (payload.orderId() == null || payload.orderId().isBlank()
                || payload.userId() == null || payload.completedAt() == null
                || payload.items() == null || payload.items().isEmpty()) {
            throw new InboundEvents.ContractViolationException(
                    "ORDER_COMPLETED is missing order_id, user_id, completed_at or items", null);
        }
        for (OrderCompletedPayload.Item item : payload.items()) {
            if (item.productId() == null || item.productId().isBlank()
                    || item.skuId() == null || item.skuId().isBlank()) {
                log.error("ORDER_COMPLETED for order {} carries an item without product_id or sku_id",
                        payload.orderId());
                throw new InboundEvents.ContractViolationException(
                        "ORDER_COMPLETED item is missing product_id or sku_id", null);
            }
        }
    }
}
