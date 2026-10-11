package com.platform.paymentservice.adapter.in.http;

import com.platform.paymentservice.application.service.WebhookReplayCommand;
import com.platform.paymentservice.application.service.WebhookReplayResult;
import com.platform.paymentservice.application.service.WebhookReplayService;
import com.platform.paymentservice.application.port.out.FinancialOperationsQueryPort;
import com.platform.paymentservice.application.service.ReconciliationOperationsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.context.annotation.Import(PaymentHttpConfiguration.class)
@WebMvcTest(PaymentOperationsController.class)
class PaymentOperationsControllerTest {
    @Autowired MockMvc mvc;
    @MockBean WebhookReplayService replays;
    @MockBean FinancialOperationsQueryPort financialQueries;
    @MockBean ReconciliationOperationsService reconciliation;

    @Test
    void schedules_replay_with_required_attribution() throws Exception {
        when(replays.replay(any(WebhookReplayCommand.class), anyString()))
                .thenReturn(new WebhookReplayResult(WebhookReplayResult.Outcome.SCHEDULED, 202));

        mvc.perform(post("/internal/v1/payment/webhooks/{id}/replay", "evt_123")
                        .header("X-Actor", "maker-user")
                        .header("X-Approver", "checker-user")
                        .header("X-Correlation-Id", "corr-1")
                        .header("Idempotency-Key", "replay-idempotency-0001")
                        .contentType("application/json")
                        .content("{\"cause_ref\":\"INC-1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "private, no-store"));
    }

    @Test
    void rejects_same_maker_and_checker() throws Exception {
        mvc.perform(post("/internal/v1/payment/webhooks/{id}/replay", "evt_123")
                        .header("X-Actor", "same-user")
                        .header("X-Approver", "same-user")
                        .header("X-Correlation-Id", "corr-1")
                        .header("Idempotency-Key", "replay-idempotency-0002")
                        .contentType("application/json")
                        .content("{\"cause_ref\":\"INC-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missing_frozen_field_cannot_silently_unfreeze() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/internal/v1/payment/reconciliation/freeze")
                .header("X-Actor", "maker-user").header("X-Approver", "checker-user")
                .header("X-Correlation-Id", "corr-freeze").header("Idempotency-Key", "freeze-idempotency-0001")
                .contentType("application/json").content("{\"cause_ref\":\"INC-1\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        org.mockito.Mockito.verifyNoInteractions(reconciliation);
    }

    @Test
    void returns_immutable_journal_with_contract_field_names() throws Exception {
        when(financialQueries.findJournal("FJN_01K5V7M4R8Q2K7V6W5X1Y0ZABE")).thenReturn(Optional.of(
                new FinancialOperationsQueryPort.Journal("FJN_01K5V7M4R8Q2K7V6W5X1Y0ZABE", "CAPTURE", 1000,
                        "VND", Instant.parse("2026-09-25T10:00:00Z"), List.of(
                        new FinancialOperationsQueryPort.Line((short) 1,
                                "PROVIDER_RECEIVABLE", "DEBIT", 1000, "VND"),
                        new FinancialOperationsQueryPort.Line((short) 2,
                                "CUSTOMER_PAYMENT_CLEARING", "CREDIT", 1000, "VND")))));

        mvc.perform(get("/internal/v1/payment/ledger/journals/{id}", "FJN_01K5V7M4R8Q2K7V6W5X1Y0ZABE")
                        .header("X-Actor", "finance-user")
                        .header("X-Correlation-Id", "corr-journal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journal_id").value("FJN_01K5V7M4R8Q2K7V6W5X1Y0ZABE"))
                .andExpect(jsonPath("$.lines[0].line_no").value(1));
    }
}
