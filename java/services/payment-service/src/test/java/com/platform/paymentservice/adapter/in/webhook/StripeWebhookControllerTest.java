package com.platform.paymentservice.adapter.in.webhook;

import com.platform.paymentservice.application.service.WebhookIngressResult;
import com.platform.paymentservice.application.service.WebhookIngressService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StripeWebhookController.class)
@TestPropertySource(properties = "payment.stripe.webhook-max-bytes=8")
class StripeWebhookControllerTest {
    @Autowired MockMvc mvc;
    @MockBean WebhookIngressService ingress;

    @Test
    void passes_the_exact_request_bytes_and_returns_200_only_for_accepted_delivery() throws Exception {
        byte[] body = new byte[]{0x7b, 0x22, 0x78, 0x22, 0x3a, 0x31, 0x7d};
        when(ingress.ingest(any(), anyString())).thenReturn(new WebhookIngressResult.Accepted());

        mvc.perform(post("/v1/payments/webhook/stripe")
                        .header("Stripe-Signature", "t=1,v1=sig")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));

        ArgumentCaptor<byte[]> captured = ArgumentCaptor.forClass(byte[].class);
        verify(ingress).ingest(captured.capture(), anyString());
        assertThat(captured.getValue()).containsExactly(body);
    }

    @Test
    void rejects_body_over_the_hard_limit_before_application_processing() throws Exception {
        mvc.perform(post("/v1/payments/webhook/stripe")
                        .header("Stripe-Signature", "t=1,v1=sig")
                        .contentType("application/json").content(new byte[9]))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(ingress, never()).ingest(any(), any());
    }

    @Test
    void maps_invalid_and_dependency_failure_without_exposing_a_body() throws Exception {
        when(ingress.ingest(any(), any())).thenReturn(
                new WebhookIngressResult.Rejected(), new WebhookIngressResult.Unavailable());

        mvc.perform(post("/v1/payments/webhook/stripe").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/payments/webhook/stripe")
                        .header("Stripe-Signature", "t=1,v1=sig").content("{}"))
                .andExpect(status().isServiceUnavailable());
    }
}
