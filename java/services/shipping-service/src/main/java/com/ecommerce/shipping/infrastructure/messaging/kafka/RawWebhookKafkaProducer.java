package com.ecommerce.shipping.infrastructure.messaging.kafka;

import com.ecommerce.shipping.application.dto.ReceiveWebhookCommand;
import com.ecommerce.shipping.application.port.out.RawWebhookPublisherPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

// Theo thiết kế shipping_workflow.md mục 5: dump raw webhook vào t.shipping.webhooks.raw.
@Component
public class RawWebhookKafkaProducer implements RawWebhookPublisherPort {

	private final KafkaTemplate<String, Object> kafkaTemplate;

	@Value("${shipping.topics.webhooks-raw:t.shipping.webhooks.raw}")
	private String rawWebhookTopic;

	public RawWebhookKafkaProducer(KafkaTemplate<String, Object> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	@Override
	public void publishRawWebhook(ReceiveWebhookCommand command) {
		String key = command.provider() + ":" + (command.trackingNumber() == null ? "unknown" : command.trackingNumber());
		Map<String, Object> envelope = new HashMap<>();
		envelope.put("provider", command.provider());
		envelope.put("tracking_number", command.trackingNumber());
		envelope.put("raw_body", command.rawBody());
		envelope.put("headers", command.headers());
		kafkaTemplate.send(rawWebhookTopic, key, envelope);
	}
}
