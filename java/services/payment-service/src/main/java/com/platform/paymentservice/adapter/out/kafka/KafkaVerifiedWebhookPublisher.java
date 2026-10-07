package com.platform.paymentservice.adapter.out.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.port.out.VerifiedWebhook;
import com.platform.paymentservice.application.port.out.VerifiedWebhookPublisherPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Component
public final class KafkaVerifiedWebhookPublisher implements VerifiedWebhookPublisherPort {
    private static final String EVENT_TYPE = "com.ecommerce.payment.stripe-webhook.received.v1";
    private static final String SOURCE = "/services/payment/webhook-ingress";
    private static final String DATA_SCHEMA =
            "/contracts/events/payment/internal/v1/schemas/stripe-webhook-received.schema.json";
    private static final String TOPIC = "t.payment.webhooks.raw";

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public KafkaVerifiedWebhookPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    @Override
    public void publish(Message message) {
        VerifiedWebhook webhook = message.webhook();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", "STRIPE");
        data.put("stripe_event_id", webhook.stripeEventId());
        data.put("event_type", webhook.eventType());
        data.put("event_created_at", webhook.eventCreatedAt());
        data.put("api_version", webhook.apiVersion());
        data.put("livemode", webhook.livemode());
        data.put("received_at", message.receivedAt());
        data.put("verified_at", message.verifiedAt());
        data.put("signing_secret_id", webhook.signingSecretId());
        data.put("raw_body_base64", message.rawBodyBase64());
        data.put("raw_body_sha256", message.rawBodySha256());
        data.put("stripe_signature", message.stripeSignature());

        Map<String, Object> cloudEvent = new LinkedHashMap<>();
        cloudEvent.put("specversion", "1.0");
        cloudEvent.put("id", message.id());
        cloudEvent.put("source", SOURCE);
        cloudEvent.put("subject", "stripe-events/" + webhook.stripeEventId());
        cloudEvent.put("type", EVENT_TYPE);
        cloudEvent.put("time", message.time());
        cloudEvent.put("datacontenttype", "application/json");
        cloudEvent.put("dataschema", DATA_SCHEMA);
        cloudEvent.put("data", data);

        try {
            String value = json.writeValueAsString(cloudEvent);
            kafka.send(TOPIC, "STRIPE:" + webhook.stripeEventId(), value).get();
        } catch (JsonProcessingException | ExecutionException failure) {
            throw new IllegalStateException("verified webhook Kafka acknowledgement failed", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("verified webhook Kafka acknowledgement interrupted", interrupted);
        }
    }
}
