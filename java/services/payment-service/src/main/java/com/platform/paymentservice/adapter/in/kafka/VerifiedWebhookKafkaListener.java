package com.platform.paymentservice.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.service.VerifiedWebhookNotification;
import com.platform.paymentservice.application.service.WebhookProcessorService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = {"payment.stripe.webhook-secrets", "payment.crypto.local-key",
        "payment.stripe.secret-key"})
public final class VerifiedWebhookKafkaListener {
    private static final String CLOUD_EVENT_TYPE =
            "com.ecommerce.payment.stripe-webhook.received.v1";
    private final WebhookProcessorService processor;
    private final ObjectMapper json;

    public VerifiedWebhookKafkaListener(WebhookProcessorService processor, ObjectMapper json) {
        this.processor = processor;
        this.json = json;
    }

    @KafkaListener(topics = "t.payment.webhooks.raw", groupId = "payment-webhook-processor",
            autoStartup = "${payment.webhook.processor-enabled:true}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        VerifiedWebhookNotification notification = parse(record);
        processor.process(notification, owner(record));
        acknowledgment.acknowledge();
    }

    private VerifiedWebhookNotification parse(ConsumerRecord<String, String> record) {
        try {
            JsonNode envelope = json.readTree(record.value());
            if (!"1.0".equals(envelope.path("specversion").asText())
                    || !CLOUD_EVENT_TYPE.equals(envelope.path("type").asText())
                    || !"/services/payment/webhook-ingress".equals(envelope.path("source").asText())) {
                throw new IllegalArgumentException("unsupported verified webhook CloudEvent");
            }
            JsonNode data = envelope.path("data");
            String eventId = required(data, "stripe_event_id");
            if (!Objects.equals(record.key(), "STRIPE:" + eventId)
                    || !Objects.equals(envelope.path("subject").asText(), "stripe-events/" + eventId)) {
                throw new IllegalArgumentException("verified webhook Kafka identity mismatch");
            }
            return new VerifiedWebhookNotification(eventId, required(data, "event_type"),
                    Instant.parse(required(data, "event_created_at")),
                    required(data, "raw_body_base64"), required(data, "raw_body_sha256"));
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid verified webhook CloudEvent", invalid);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static String owner(ConsumerRecord<String, String> record) {
        return "kafka:" + record.topic() + ":" + record.partition() + ":" + record.offset();
    }
}
