package com.ecommerce.shipping.infrastructure.messaging.kafka;

import com.ecommerce.shipping.domain.model.OutboxEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

// Theo thiết kế shipping_workers.md mục 1: outbox polling worker publish Kafka t.shipping.events.
@Component
public class ShippingOutboxPublisher {

	private final KafkaTemplate<String, Object> kafkaTemplate;

	@Value("${shipping.topics.events:t.shipping.events}")
	private String eventsTopic;

	public ShippingOutboxPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	public void publish(OutboxEvent event) {
		String key = resolvePartitionKey(event.payload());
		Map<String, Object> envelope = Map.of(
				"event_id", event.id().toString(),
				"event_type", event.eventType(),
				"source", "shipping-service",
				"timestamp", event.createdAt().toString(),
				"correlation_id", String.valueOf(event.payload().getOrDefault("order_id", "")),
				"payload", event.payload());
		kafkaTemplate.send(eventsTopic, key, envelope);
	}

	private String resolvePartitionKey(Map<String, Object> payload) {
		Object orderId = payload.get("order_id");
		return orderId == null ? "unknown" : String.valueOf(orderId);
	}
}
