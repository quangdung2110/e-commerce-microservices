package com.platform.paymentservice.adapter.in.kafka;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.platform.paymentservice.application.service.WebhookProcessorService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class VerifiedWebhookKafkaListenerTest {
    private static final String VALUE = """
            {"specversion":"1.0","type":"com.ecommerce.payment.stripe-webhook.received.v1",
             "source":"/services/payment/webhook-ingress","subject":"stripe-events/evt_test_1",
             "data":{"stripe_event_id":"evt_test_1","event_type":"payment_intent.succeeded",
             "event_created_at":"2026-09-22T10:00:00Z","raw_body_base64":"e30=",
             "raw_body_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}
            """;

    @Test
    void acknowledges_only_after_processor_returns() {
        WebhookProcessorService processor = mock(WebhookProcessorService.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        var listener = new VerifiedWebhookKafkaListener(processor,
                JsonMapper.builder().findAndAddModules().build());

        listener.onMessage(new ConsumerRecord<>("t.payment.webhooks.raw", 2, 10,
                "STRIPE:evt_test_1", VALUE), acknowledgment);

        var ordered = org.mockito.Mockito.inOrder(processor, acknowledgment);
        ordered.verify(processor).process(any(), anyString());
        ordered.verify(acknowledgment).acknowledge();
    }

    @Test
    void does_not_acknowledge_when_processor_cannot_take_database_responsibility() {
        WebhookProcessorService processor = mock(WebhookProcessorService.class);
        doThrow(new WebhookProcessorService.InboxUnavailableException("missing"))
                .when(processor).process(any(), anyString());
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        var listener = new VerifiedWebhookKafkaListener(processor,
                JsonMapper.builder().findAndAddModules().build());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> listener.onMessage(
                new ConsumerRecord<>("t.payment.webhooks.raw", 2, 10,
                        "STRIPE:evt_test_1", VALUE), acknowledgment))
                .isInstanceOf(WebhookProcessorService.InboxUnavailableException.class);
        verify(acknowledgment, never()).acknowledge();
    }
}
