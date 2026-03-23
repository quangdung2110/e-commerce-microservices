package com.ecommerce.shipping.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Theo thiết kế shipping_events.md mục 1: t.shipping.events và t.shipping.webhooks.raw.
@Configuration
public class KafkaTopicConfig {

	@Bean
	public NewTopic shippingEventsTopic(@Value("${shipping.topics.events:t.shipping.events}") String topicName) {
		return new NewTopic(topicName, 6, (short) 1);
	}

	@Bean
	public NewTopic shippingWebhooksRawTopic(@Value("${shipping.topics.webhooks-raw:t.shipping.webhooks.raw}") String topicName) {
		return new NewTopic(topicName, 6, (short) 1);
	}
}
