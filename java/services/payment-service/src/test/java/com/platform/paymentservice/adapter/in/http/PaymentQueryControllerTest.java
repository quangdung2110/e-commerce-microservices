package com.platform.paymentservice.adapter.in.http;

import com.platform.paymentservice.application.port.out.PaymentQueryPort;
import com.platform.paymentservice.application.service.PaymentQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentQueryController.class)
class PaymentQueryControllerTest {
    private static final String TXN = "TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABE";
    private static final String RFD = "RFD_01K5V7M4R8Q2K7V6W5X1Y0ZABF";

    @Autowired MockMvc mvc;
    @MockBean PaymentQueryService queries;

    @Test
    void payment_is_contract_shaped_with_explicit_null_refund_and_empty_disputes() throws Exception {
        when(queries.payment(TXN)).thenReturn(Optional.of(new PaymentQueryPort.PaymentView(
                TXN, 770004L, 2, 250_000L, "VND", "SUCCESS", 7L, false,
                Instant.parse("2026-10-11T01:00:00Z"), null, null, List.of())));

        mvc.perform(get("/v1/payments/{id}", TXN)
                        .header("X-Service-Identity", "order-service")
                        .header("X-Correlation-Id", "corr-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Correlation-Id", "corr-1"))
                .andExpect(jsonPath("$.transaction_id").value(TXN))
                .andExpect(jsonPath("$.order_id").value("770004"))
                .andExpect(jsonPath("$.payment_attempt_number").value(2))
                .andExpect(jsonPath("$.amount.amount_minor").value(250000))
                .andExpect(jsonPath("$.amount.currency").value("VND"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.financial_state_version").value(7))
                .andExpect(jsonPath("$.requires_manual_review").value(false))
                .andExpect(jsonPath("$.terminal_at").value((Object) null))
                .andExpect(jsonPath("$.refund").value((Object) null))
                .andExpect(jsonPath("$.disputes").isEmpty());
    }

    @Test
    void payment_embeds_its_single_refund_and_ordered_disputes() throws Exception {
        PaymentQueryPort.RefundView refund = new PaymentQueryPort.RefundView(
                RFD, TXN, 770004L, 250_000L, "VND", "SUCCESS");
        when(queries.payment(TXN)).thenReturn(Optional.of(new PaymentQueryPort.PaymentView(
                TXN, 770004L, 1, 250_000L, "VND", "REFUNDED", 9L, false,
                Instant.parse("2026-10-11T01:00:00Z"), null, refund,
                List.of(new PaymentQueryPort.DisputeView(
                        "DSP_01K5V7M4R8Q2K7V6W5X1Y0ZABG", TXN, 770004L, 250_000L, "VND", "OPEN", "UNCHANGED",
                        "FRAUDULENT", null, Instant.parse("2026-10-12T00:00:00Z"), null)))));

        mvc.perform(get("/v1/payments/{id}", TXN)
                        .header("X-Service-Identity", "order-service")
                        .header("X-Correlation-Id", "corr-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.refund_transaction_id").value(RFD))
                .andExpect(jsonPath("$.refund.status").value("SUCCESS"))
                .andExpect(jsonPath("$.disputes[0].dispute_transaction_id").value("DSP_01K5V7M4R8Q2K7V6W5X1Y0ZABG"))
                .andExpect(jsonPath("$.disputes[0].evidence_due_at").value((Object) null))
                .andExpect(jsonPath("$.disputes[0].funds_state").value("UNCHANGED"));
    }

    @Test
    void refund_is_contract_shaped() throws Exception {
        when(queries.refund(RFD)).thenReturn(Optional.of(new PaymentQueryPort.RefundView(
                RFD, TXN, 770004L, 250_000L, "VND", "FAILED")));

        mvc.perform(get("/v1/refunds/{id}", RFD)
                        .header("X-Service-Identity", "order-service")
                        .header("X-Correlation-Id", "corr-3"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.refund_transaction_id").value(RFD))
                .andExpect(jsonPath("$.payment_transaction_id").value(TXN))
                .andExpect(jsonPath("$.order_id").value("770004"))
                .andExpect(jsonPath("$.amount.amount_minor").value(250000))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void unknown_transactions_are_no_store_not_found_problems() throws Exception {
        when(queries.payment(TXN)).thenReturn(Optional.empty());
        when(queries.refund(RFD)).thenReturn(Optional.empty());

        mvc.perform(get("/v1/payments/{id}", TXN)
                        .header("X-Service-Identity", "order-service").header("X-Correlation-Id", "corr-4"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.correlation_id").value("corr-4"));
        mvc.perform(get("/v1/refunds/{id}", RFD)
                        .header("X-Service-Identity", "order-service").header("X-Correlation-Id", "corr-5"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_FOUND"));
    }

    @Test
    void wrong_workload_malformed_id_or_missing_correlation_is_a_validation_problem() throws Exception {
        mvc.perform(get("/v1/payments/{id}", TXN)
                        .header("X-Service-Identity", "other-service").header("X-Correlation-Id", "corr-6"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/v1/payments/{id}", "not-a-transaction")
                        .header("X-Service-Identity", "order-service").header("X-Correlation-Id", "corr-7"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/refunds/{id}", TXN)
                        .header("X-Service-Identity", "order-service").header("X-Correlation-Id", "corr-8"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/refunds/{id}", RFD).header("X-Service-Identity", "order-service"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlation_id").value("missing-correlation-id"));
    }
}
