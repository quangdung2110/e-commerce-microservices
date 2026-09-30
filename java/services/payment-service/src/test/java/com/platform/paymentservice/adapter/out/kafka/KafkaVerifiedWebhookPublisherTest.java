package com.platform.paymentservice.adapter.out.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.platform.paymentservice.application.port.out.VerifiedWebhook;
import com.platform.paymentservice.application.port.out.VerifiedWebhookPublisherPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaVerifiedWebhookPublisherTest {
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    @Test
    void sends_the_full_structured_cloud_event_with_the_canonical_key() throws Exception {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

        new KafkaVerifiedWebhookPublisher(kafka, json).publish(message());

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(org.mockito.ArgumentMatchers.eq("t.payment.webhooks.raw"),
                org.mockito.ArgumentMatchers.eq("STRIPE:evt_test_1"), value.capture());
        JsonNode event = json.readTree(value.getValue());
        assertThat(event.path("specversion").asText()).isEqualTo("1.0");
        assertThat(event.path("type").asText())
                .isEqualTo("com.ecommerce.payment.stripe-webhook.received.v1");
        assertThat(event.path("subject").asText()).isEqualTo("stripe-events/evt_test_1");
        assertThat(event.path("data").path("raw_body_base64").asText()).isEqualTo("e30=");
        assertThat(event.path("data").path("stripe_signature").asText()).isEqualTo("t=1,v1=sig");
    }

    @Test
    void does_not_report_success_when_the_broker_ack_fails() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafka.send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(failed);

        assertThatThrownBy(() -> new KafkaVerifiedWebhookPublisher(
                kafka, JsonMapper.builder().findAndAddModules().build()).publish(message()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("acknowledgement failed");
    }

    private static VerifiedWebhookPublisherPort.Message message() {
        return new VerifiedWebhookPublisherPort.Message(
                UUID.fromString("00000000-0000-4000-8000-000000000011"), NOW,
                new VerifiedWebhook("evt_test_1", "payment_intent.succeeded", NOW.minusSeconds(1),
                        "2026-08-26.dahlia", false, "stripe-webhook-current"),
                NOW, NOW, "e30=", "a".repeat(64), "t=1,v1=sig");
    }
}
