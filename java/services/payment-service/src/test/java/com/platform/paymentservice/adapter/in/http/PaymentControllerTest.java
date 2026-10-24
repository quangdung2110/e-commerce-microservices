package com.platform.paymentservice.adapter.in.http;

import com.platform.paymentservice.application.service.InitiatePaymentService;
import com.platform.paymentservice.application.service.InitiatePaymentResult;
import com.platform.paymentservice.application.service.CancelPaymentService;
import com.platform.paymentservice.application.service.CancelPaymentResult;
import com.platform.paymentservice.application.service.CancelPaymentCommand;
import com.platform.paymentservice.domain.Money;
import com.platform.paymentservice.application.service.PaymentClientDataResult;
import com.platform.paymentservice.application.service.PaymentClientDataService;
import com.platform.paymentservice.application.service.RefundPaymentCommand;
import com.platform.paymentservice.application.service.RefundPaymentResult;
import com.platform.paymentservice.application.service.RefundPaymentService;
import com.platform.paymentservice.domain.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.context.annotation.Import(PaymentHttpConfiguration.class)
@WebMvcTest(PaymentController.class)
class PaymentControllerTest {
    private static final String TXN = "TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABE";

    @Autowired MockMvc mvc;
    @MockBean InitiatePaymentService initiate;
    @MockBean PaymentClientDataService clientData;
    @MockBean CancelPaymentService cancel;
    @MockBean RefundPaymentService refund;

    @Test
    void certified_create_rejection_returns_identity_bound_422_without_client_data() throws Exception {
        when(initiate.initiate(any(), anyString())).thenReturn(new InitiatePaymentResult.NoPaymentCreated(
                TXN, "POP_01K5V7M4R8Q2K7V6W5X1Y0ZABD", 10001, 1));
        mvc.perform(post("/v1/payments/initiate")
                        .header("Idempotency-Key", "initiate-key-123456789")
                        .header("X-Service-Identity", "order-service").header("X-Correlation-Id", "small-amount")
                        .contentType("application/json")
                        .content("{\"order_id\":\"10001\",\"payment_attempt_number\":1,\"amount\":{\"amount_minor\":1,\"currency\":\"VND\"},\"user_id\":\"00000000-0000-4000-8000-000000000001\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.code").value("PROVIDER_CREATE_NOT_ACCEPTED"))
                .andExpect(jsonPath("$.no_payment_created").value(true))
                .andExpect(jsonPath("$.order_id").value("10001"))
                .andExpect(jsonPath("$.payment_attempt_number").value(1))
                .andExpect(jsonPath("$.client_secret").doesNotExist());
    }

    @Test
    void client_data_response_is_private_no_store_and_contract_shaped() throws Exception {
        when(clientData.get(TXN)).thenReturn(new PaymentClientDataResult.Usable(
                TXN, 1, PaymentStatus.PENDING, "pi_client_secret".toCharArray(),
                "pk_test_contract", Instant.parse("2026-09-22T10:14:30Z")));

        mvc.perform(get("/v1/payments/{transactionId}/client-data", TXN)
                        .header("X-Service-Identity", "order-service")
                        .header("X-Correlation-Id", "checkout-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Correlation-Id", "checkout-1"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.client_secret").value("pi_client_secret"))
                .andExpect(jsonPath("$.publishable_key").value("pk_test_contract"));
    }

    @Test
    void unusable_response_has_explicit_null_client_fields() throws Exception {
        when(clientData.get(TXN)).thenReturn(new PaymentClientDataResult.Unusable(
                TXN, 1, PaymentStatus.PROCESSING, Instant.parse("2026-09-22T10:14:30Z")));

        mvc.perform(get("/v1/payments/{transactionId}/client-data", TXN)
                        .header("X-Service-Identity", "order-service")
                        .header("X-Correlation-Id", "checkout-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.client_secret").value((Object) null))
                .andExpect(jsonPath("$.publishable_key").value((Object) null));
    }

    @Test
    void a_caller_outside_the_allowlist_is_a_no_store_forbidden_problem() throws Exception {
        mvc.perform(get("/v1/payments/{transactionId}/client-data", TXN)
                        .header("X-Service-Identity", "other-service")
                        .header("X-Correlation-Id", "checkout-3"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void cancel_success_response_matches_discriminated_contract_and_is_no_store() throws Exception {
        when(cancel.cancel(any(CancelPaymentCommand.class), anyString()))
                .thenReturn(new CancelPaymentResult.AlreadySucceeded(
                        TXN, 10001, new Money(995000, "VND"), 2));

        mvc.perform(post("/v1/payments/{transactionId}/cancel", TXN)
                        .header("Idempotency-Key", "cancel-key-123456789")
                        .header("X-Service-Identity", "order-service")
                        .header("X-Correlation-Id", "cancel-1")
                        .contentType("application/json")
                        .content("{\"order_id\":\"10001\",\"reason\":\"PAYMENT_TIMEOUT\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.outcome").value("ALREADY_SUCCEEDED"))
                .andExpect(jsonPath("$.amount.amount_minor").value(995000))
                .andExpect(jsonPath("$.financial_state_version").value(2));
    }

    @Test
    void non_capture_cancel_response_omits_amount_as_required_by_openapi() throws Exception {
        when(cancel.cancel(any(CancelPaymentCommand.class), anyString()))
                .thenReturn(new CancelPaymentResult.Canceled(TXN, 10001, 2));

        mvc.perform(post("/v1/payments/{transactionId}/cancel", TXN)
                        .header("Idempotency-Key", "cancel-key-123456789")
                        .header("X-Service-Identity", "order-service")
                        .header("X-Correlation-Id", "cancel-2")
                        .contentType("application/json")
                        .content("{\"order_id\":\"10001\",\"reason\":\"BUYER_CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CANCELED"))
                .andExpect(jsonPath("$.amount").doesNotExist());
    }

    @Test
    void refund_command_accepts_contract_evidence_and_returns_processing() throws Exception {
        when(refund.refund(any(RefundPaymentCommand.class), anyString()))
                .thenReturn(new RefundPaymentResult.Processing(
                        "RFD_01K5V7M4R8Q2K7V6W5X1Y0ZABC", TXN));

        mvc.perform(post("/v1/payments/{transactionId}/refund", TXN)
                        .header("Idempotency-Key", "refund-command-key-0001")
                        .header("X-Service-Identity", "order-service")
                        .header("X-Correlation-Id", "refund-1")
                        .contentType("application/json")
                        .content("""
                                {"order_id":"10001","refund_request_id":"request-1",
                                 "amount":{"amount_minor":995000,"currency":"VND"},
                                 "reason":"CUSTOMER_RETURN_SELECTED_LINES",
                                 "inventory_evidence":{"type":"RETURN_SCOPE_INSPECTION_COMPLETED",
                                  "refund_scope_id":"scope-1","expected":2,"received":2,
                                  "passed":1,"failed":1}}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.refund_transaction_id")
                        .value("RFD_01K5V7M4R8Q2K7V6W5X1Y0ZABC"))
                .andExpect(jsonPath("$.payment_transaction_id").value(TXN))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void malformed_unknown_and_coerced_fields_are_no_store_validation_problems() throws Exception {
        for (String body : java.util.List.of("{", "{\"order_id\":10001,\"reason\":\"BUYER_CANCELLED\"}",
                "{\"order_id\":\"10001\",\"reason\":\"BUYER_CANCELLED\",\"extra\":true}")) {
            mvc.perform(post("/v1/payments/{transactionId}/cancel", TXN)
                    .header("Idempotency-Key", "cancel-key-123456789").header("X-Service-Identity", "order-service")
                    .header("X-Correlation-Id", "invalid-json").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(content().contentType("application/problem+json"))
                    .andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        org.mockito.Mockito.verifyNoInteractions(cancel);
    }

    @Test
    void overlong_correlation_and_short_idempotency_are_rejected_before_command_execution() throws Exception {
        for (var headers : java.util.List.of(new String[]{"x".repeat(129), "cancel-command-key-0001"},
                new String[]{"corr-valid", "short-key"}))
            mvc.perform(post("/v1/payments/{transactionId}/cancel", TXN)
                    .header("Idempotency-Key", headers[1]).header("X-Service-Identity", "order-service")
                    .header("X-Correlation-Id", headers[0]).contentType("application/json")
                    .content("{\"order_id\":\"10001\",\"reason\":\"BUYER_CANCELLED\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(
                            headers[1].length() < 16 ? "IDEMPOTENCY_KEY_MISSING" : "VALIDATION_FAILED"));
        org.mockito.Mockito.verifyNoInteractions(cancel);
    }

    @Test
    void database_failure_is_a_retryable_dependency_problem() throws Exception {
        when(clientData.get(TXN)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private DB detail"));
        mvc.perform(get("/v1/payments/{transactionId}/client-data", TXN)
                .header("X-Service-Identity", "order-service").header("X-Correlation-Id", "db-unavailable"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true)).andExpect(header().string("Cache-Control", "private, no-store"));
    }

    @Test
    void fractional_money_is_rejected_and_refund_business_errors_use_http_400() throws Exception {
        String body = "{\"order_id\":\"10001\",\"refund_request_id\":\"request-1\",\"amount\":{\"amount_minor\":995000,\"currency\":\"VND\"},\"reason\":\"ADMIN_FORCE_REFUND_BEFORE_DISPATCH\",\"inventory_evidence\":{\"type\":\"COMMITTED_ORDER_RELEASED_BEFORE_DISPATCH\",\"release_reference\":\"release-1\"}}";
        mvc.perform(post("/v1/payments/{transactionId}/refund", TXN)
                .header("Idempotency-Key", "refund-command-key-0001").header("X-Service-Identity", "order-service")
                .header("X-Correlation-Id", "refund-errors").contentType("application/json")
                .content(body.replace("995000", "995000.5")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        org.mockito.Mockito.verifyNoInteractions(refund);
        for (var result : java.util.List.of(new RefundPaymentResult.PaymentNotSuccess(), new RefundPaymentResult.AmountExceeded())) {
            when(refund.refund(any(RefundPaymentCommand.class), anyString())).thenReturn(result);
            mvc.perform(post("/v1/payments/{transactionId}/refund", TXN)
                    .header("Idempotency-Key", "refund-command-key-0001").header("X-Service-Identity", "order-service")
                    .header("X-Correlation-Id", "refund-errors").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void refund_reason_and_evidence_mismatch_is_rejected_before_service_call() throws Exception {
        mvc.perform(post("/v1/payments/{transactionId}/refund", TXN)
                        .header("Idempotency-Key", "refund-command-key-0001")
                        .header("X-Service-Identity", "order-service")
                        .header("X-Correlation-Id", "refund-2")
                        .contentType("application/json")
                        .content("""
                                {"order_id":"10001","refund_request_id":"request-1",
                                 "amount":{"amount_minor":995000,"currency":"VND"},
                                 "reason":"ADMIN_FORCE_REFUND_BEFORE_DISPATCH",
                                 "inventory_evidence":{"type":"RETURN_SCOPE_INSPECTION_COMPLETED",
                                  "refund_scope_id":"scope-1","expected":1,"received":1,
                                  "passed":1,"failed":0}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
