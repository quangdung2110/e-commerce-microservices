package com.platform.paymentservice.adapter.in.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contracts ADR-004 for Payment: the Order-facing routes (ADR-026) and the Finance Ops routes (ADR-028) each need
 * a named caller on their own allowlist, any other internal path rejects everyone, the public webhook and the
 * actuator are not checked, and encoding cannot skip the check.
 */
class ServiceIdentityFilterTest {

    @RestController
    static class Stub {
        @GetMapping("/v1/payments/{id}")
        String payment(@PathVariable String id) {
            return "reached";
        }

        @GetMapping("/v1/refunds/{id}")
        String refund(@PathVariable String id) {
            return "reached";
        }

        @GetMapping("/v1/payments/webhook/stripe")
        String webhook() {
            return "reached";
        }

        @GetMapping("/actuator/health")
        String health() {
            return "reached";
        }

        @GetMapping("/internal/v1/payment/review-cases")
        String operations() {
            return "reached";
        }

        @GetMapping("/internal/v1/other")
        String otherInternal() {
            return "reached";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Stub())
                .addFilters(new ServiceIdentityFilter(List.of("order-service", " "), List.of("finance-ops", ""),
                        new ObjectMapper()))
                .build();
    }

    private int status(String path, String identity) throws Exception {
        var request = MockMvcRequestBuilders.get(URI.create(path)).header("X-Correlation-Id", "corr-1");
        if (identity != null) {
            request.header("X-Service-Identity", identity);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void anAllowedCallerReachesPaymentsAndRefunds() throws Exception {
        assertThat(status("/v1/payments/TXN_1", "order-service")).isEqualTo(200);
        assertThat(status("/v1/refunds/TXN_1", "order-service")).isEqualTo(200);
    }

    @Test
    void aMissingOrBlankIdentityIs401WithAProblem() throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders.get("/v1/payments/TXN_1").header("X-Correlation-Id", "corr-9"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHENTICATED\"", "\"retryable\":false",
                "\"correlation_id\":\"corr-9\"");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(status("/v1/payments/TXN_1", "   ")).isEqualTo(401);
    }

    @Test
    void aCallerOutsideTheAllowlistIs403() throws Exception {
        assertThat(status("/v1/payments/TXN_1", "search-service")).isEqualTo(403);
        assertThat(status("/v1/refunds/TXN_1", "search-service")).isEqualTo(403);
    }

    @Test
    void anOversizedCorrelationIdIsReplacedInTheProblem() throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders.get("/v1/payments/TXN_1")
                        .header("X-Correlation-Id", "c".repeat(200)).header("X-Service-Identity", "nobody"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"correlation_id\":\"missing-correlation-id\"");
    }

    @Test
    void encodingOrPathParametersDoNotSkipTheCheck() throws Exception {
        assertThat(status("/v1/%70ayments/TXN_1", null)).isEqualTo(401);
        assertThat(status("/v1/payments;x=1/TXN_1", null)).isEqualTo(401);
    }

    @Test
    void thePublicWebhookAndTheActuatorAreNotChecked() throws Exception {
        assertThat(status("/v1/payments/webhook/stripe", null)).isEqualTo(200);
        assertThat(status("/actuator/health", null)).isEqualTo(200);
    }

    // Payment ADR-028: the Finance Ops routes have their own allowlist.
    @Test
    void financeOpsRoutesNeedAnOperationsCaller() throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders.get("/internal/v1/payment/review-cases")
                        .header("X-Correlation-Id", "corr-ops"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHENTICATED\"", "\"retryable\":false",
                "\"correlation_id\":\"corr-ops\"");
        assertThat(status("/internal/v1/payment/review-cases", " ")).isEqualTo(401);
        assertThat(status("/internal/v1/payment/review-cases", "order-service")).isEqualTo(403);
        assertThat(status("/internal/v1/payment/review-cases", "finance-ops")).isEqualTo(200);
    }

    @Test
    void anOperationsCallerCannotUseTheOrderFacingRoutes() throws Exception {
        assertThat(status("/v1/payments/TXN_1", "finance-ops")).isEqualTo(403);
        assertThat(status("/v1/refunds/TXN_1", "finance-ops")).isEqualTo(403);
    }

    @Test
    void encodingOrPathParametersDoNotSkipTheOperationsCheck() throws Exception {
        assertThat(status("/internal/v1/%70ayment/review-cases", null)).isEqualTo(401);
        assertThat(status("/internal/v1/payment;x=1/review-cases", null)).isEqualTo(401);
        assertThat(status("/internal/v1/payment/review-cases;x=1", "order-service")).isEqualTo(403);
    }

    @Test
    void anInternalPathOutsideEveryGroupRejectsEveryCaller() throws Exception {
        assertThat(status("/internal/v1/other", null)).isEqualTo(401);
        assertThat(status("/internal/v1/other", "finance-ops")).isEqualTo(403);
        assertThat(status("/internal/v1/other", "order-service")).isEqualTo(403);
    }

    @Test
    void anEmptyAllowlistRejectsEveryCaller() throws Exception {
        MockMvc closed = MockMvcBuilders.standaloneSetup(new Stub())
                .addFilters(new ServiceIdentityFilter(List.of(), List.of(), new ObjectMapper())).build();

        int payment = closed.perform(MockMvcRequestBuilders.get("/v1/payments/TXN_1")
                .header("X-Service-Identity", "order-service")).andReturn().getResponse().getStatus();
        int operations = closed.perform(MockMvcRequestBuilders.get("/internal/v1/payment/review-cases")
                .header("X-Service-Identity", "finance-ops")).andReturn().getResponse().getStatus();

        assertThat(payment).isEqualTo(403);
        assertThat(operations).isEqualTo(403);
    }
}
