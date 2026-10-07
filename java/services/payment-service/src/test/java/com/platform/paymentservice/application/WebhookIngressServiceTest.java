package com.platform.paymentservice.application;

import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.EncryptedSecret;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.SecretCipherPort;
import com.platform.paymentservice.application.port.out.VerifiedWebhook;
import com.platform.paymentservice.application.port.out.VerifiedWebhookPublisherPort;
import com.platform.paymentservice.application.port.out.WebhookInboxPort;
import com.platform.paymentservice.application.port.out.WebhookVerificationPort;
import com.platform.paymentservice.application.service.WebhookIngressResult;
import com.platform.paymentservice.application.service.WebhookIngressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookIngressServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final byte[] BODY = "{\"id\":\"evt_test_1\"}".getBytes(StandardCharsets.UTF_8);
    private static final String SIGNATURE = "t=1789984800,v1=signature";
    private static final VerifiedWebhook VERIFIED = new VerifiedWebhook(
            "evt_test_1", "payment_intent.succeeded", NOW.minusSeconds(1),
            "2026-08-26.dahlia", false, "stripe-webhook-current");

    private WebhookVerificationPort verifier;
    private WebhookInboxPort inbox;
    private VerifiedWebhookPublisherPort publisher;
    private SecretCipherPort cipher;
    private WebhookIngressService service;

    @BeforeEach
    void setUp() throws Exception {
        verifier = mock(WebhookVerificationPort.class);
        inbox = mock(WebhookInboxPort.class);
        publisher = mock(VerifiedWebhookPublisherPort.class);
        cipher = mock(SecretCipherPort.class);
        IdGeneratorPort ids = mock(IdGeneratorPort.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        when(ids.nextEventId()).thenReturn(UUID.fromString("00000000-0000-4000-8000-000000000011"));
        when(verifier.verify(any(), any(), any())).thenReturn(VERIFIED);
        when(cipher.encrypt(any(), any())).thenReturn(new EncryptedSecret(new byte[]{1, 2, 3}, "key-1"));
        when(inbox.saveVerified(any())).thenReturn(WebhookInboxPort.SaveResult.READY_TO_PUBLISH);
        service = new WebhookIngressService(verifier, inbox, publisher, cipher, ids, clock);
    }

    @Test
    void encrypts_and_persists_before_waiting_for_publish_ack() {
        WebhookIngressResult result = service.ingest(BODY, SIGNATURE);

        assertThat(result).isInstanceOf(WebhookIngressResult.Accepted.class);
        InOrder order = inOrder(inbox, publisher);
        order.verify(inbox).saveVerified(any());
        order.verify(publisher).publish(any());
        ArgumentCaptor<VerifiedWebhookPublisherPort.Message> message =
                ArgumentCaptor.forClass(VerifiedWebhookPublisherPort.Message.class);
        verify(publisher).publish(message.capture());
        assertThat(message.getValue().rawBodyBase64()).isEqualTo("eyJpZCI6ImV2dF90ZXN0XzEifQ==");
        assertThat(message.getValue().rawBodySha256()).hasSize(64);
        assertThat(message.getValue().stripeSignature()).isEqualTo(SIGNATURE);
    }

    @Test
    void invalid_delivery_never_reaches_inbox_or_kafka() throws Exception {
        when(verifier.verify(any(), any(), any()))
                .thenThrow(new WebhookVerificationPort.InvalidWebhookException("invalid"));

        assertThat(service.ingest(BODY, SIGNATURE))
                .isInstanceOf(WebhookIngressResult.Rejected.class);
        verify(inbox, never()).saveVerified(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void duplicate_terminal_is_acknowledged_without_republishing() {
        when(inbox.saveVerified(any())).thenReturn(WebhookInboxPort.SaveResult.DUPLICATE_TERMINAL);

        assertThat(service.ingest(BODY, SIGNATURE))
                .isInstanceOf(WebhookIngressResult.DuplicateTerminal.class);
        verify(publisher, never()).publish(any());
    }

    @Test
    void kafka_failure_returns_unavailable_after_the_inbox_is_durable() {
        doThrow(new IllegalStateException("broker unavailable")).when(publisher).publish(any());

        assertThat(service.ingest(BODY, SIGNATURE))
                .isInstanceOf(WebhookIngressResult.Unavailable.class);
        verify(inbox).saveVerified(any());
    }
}
