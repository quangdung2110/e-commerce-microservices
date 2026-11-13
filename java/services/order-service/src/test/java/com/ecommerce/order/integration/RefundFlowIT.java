package com.ecommerce.order.integration;

import com.ecommerce.order.support.AbstractIntegrationTest;
import com.ecommerce.order.support.ContractSchemas;
import com.ecommerce.order.support.Fixtures;
import com.ecommerce.order.workflow.WorkflowNames;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/** WF-ORDER-007/008/008a/010 against PostgreSQL and in-memory Temporal. */
class RefundFlowIT extends AbstractIntegrationTest {

    private record Placed(long orderId, String user, List<Long> lines) {
    }

    private Placed completed() throws Exception {
        String user = UUID.randomUUID().toString();
        long orderId = completedOrder(Fixtures.submission(UUID.randomUUID().toString(), user, Fixtures.defaultLines(),
                200_000, "promo-" + UUID.randomUUID()));
        return new Placed(orderId, user, lineIds(orderId));
    }

    /** Return A×2 and C×3 out of A×2/B×1/C×3. */
    private Response returnAC(Placed placed) throws Exception {
        Map<String, Object> body = Map.of("reason_code", "DAMAGED", "lines", List.of(
                Map.of("order_line_id", placed.lines().get(0).toString(), "quantity", 2),
                Map.of("order_line_id", placed.lines().get(2).toString(), "quantity", 3)));
        return buyerPost("/api/v1/orders/" + placed.orderId() + "/return-requests", placed.user(), body);
    }

    private void awaitCase(long orderId, String status) {
        await("case " + status, () -> status.equals(jdbc.queryForObject(
                "SELECT case_status FROM order_refund_cases WHERE order_id = ?", String.class, orderId)));
    }

    private String finalization(long orderId) {
        return jdbc.queryForObject("SELECT inventory_scope_finalization_status FROM order_refund_cases WHERE order_id = ?",
                String.class, orderId);
    }

    private long expectedRefund(long orderId, List<Long> lines) {
        return jdbc.queryForObject("SELECT SUM(net_amount_minor) FROM order_item_lines WHERE order_id = ? AND id IN (?, ?)",
                Long.class, orderId, lines.get(0), lines.get(2));
    }

    @Test
    void buyerReturnRefundsOnceAndClosesTheScope() throws Exception {
        Placed placed = completed();
        long orderId = placed.orderId();
        Response accepted = returnAC(placed);
        assertThat(accepted.status()).as(String.valueOf(accepted.body())).isEqualTo(202);
        assertThat(accepted.body().path("refund_request_id").asText()).startsWith("rfr_");
        ContractSchemas.assertComponent(ContractSchemas.PUBLIC, "ReturnOperationAccepted", accepted.body());
        String operationId = accepted.body().path("operation_id").asText();
        assertThat(orderStatus(orderId)).isEqualTo("RETURN_REQUESTED");

        // One-shot guard: a new key cannot open a second return.
        Response again = returnAC(placed);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().path("code").asText()).isEqualTo("REFUND_CUSTOMER_REQUEST_ALREADY_USED");

        awaitCase(orderId, "WAITING_INVENTORY_SCOPE_INSPECTION");
        long expected = expectedRefund(orderId, placed.lines());
        assertThat(jdbc.queryForObject("SELECT refund_amount_locked_minor FROM order_refund_cases WHERE order_id = ?",
                Long.class, orderId)).isEqualTo(expected);

        // Mixed PASS/FAIL is still completion: Payment is called exactly once.
        inspectionCompleted(orderId, new long[][]{{placed.lines().get(0), 2, 1}, {placed.lines().get(2), 3, 3}});
        await("refund bound", () -> jdbc.queryForObject(
                "SELECT refund_transaction_id FROM order_refund_cases WHERE order_id = ?", String.class, orderId) != null);
        assertThat(fakes.callsFor(orderId)).containsOnlyOnce("payment.refund:" + orderId + ":" + expected);
        assertThat(fakes.behaviour.get("refundBody:" + orderId)).contains("\"received\":5").contains("\"failed\":1");
        assertThat(fakes.callsFor(orderId)).noneMatch(c -> c.startsWith("promotion.cancel"));

        refundEvent(orderId, "refund.processing", Map.of());
        refundEvent(orderId, "refund.succeeded", Map.of());
        awaitStatus(orderId, "REFUNDED");
        await("scope closed", () -> "COMPLETED".equals(finalization(orderId)));
        assertThat(fakes.callsFor(orderId)).contains("inventory.closeScope:" + orderId + ":REFUNDED");
        await("operation succeeded", () -> "SUCCEEDED".equals(operationStatus(operationId)));
        assertThat(notificationTemplates(orderId)).containsExactly("ORDER_CONFIRMED", "ORDER_COMPLETED", "ORDER_REFUNDED");
        validateAgainstContracts(orderId);
        Response refundCase = buyerGet("/api/v1/orders/" + orderId + "/refund-case", placed.user());
        assertThat(refundCase.body().path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(refundCase.body().path("scope_finalization").path("terminal_scope_state").asText()).isEqualTo("CLOSED");
        assertThat(refundCase.body().path("lines")).hasSize(2);
        ContractSchemas.assertComponent(ContractSchemas.PUBLIC, "RefundCase", refundCase.body());
        ContractSchemas.assertComponent(ContractSchemas.PUBLIC, "OrderDetail",
                buyerGet("/api/v1/orders/" + orderId, placed.user()).body());
    }

    @Test
    void partialQuantityAndUndeliveredOrdersAreRejected() throws Exception {
        Placed placed = completed();
        Response partial = buyerPost("/api/v1/orders/" + placed.orderId() + "/return-requests", placed.user(),
                Map.of("reason_code", "DAMAGED", "lines", List.of(
                        Map.of("order_line_id", placed.lines().get(0).toString(), "quantity", 1))));
        assertThat(partial.status()).isEqualTo(422);
        assertThat(partial.body().path("code").asText()).isEqualTo("INVALID_REFUND_SCOPE");
        // A failed validation does not consume the one-shot guard.
        assertThat(order(placed.orderId()).get("buyer_return_locked_at")).isNull();

        String user = UUID.randomUUID().toString();
        long processing = paidOrder(Fixtures.submission(UUID.randomUUID().toString(), user, Fixtures.defaultLines(), 0, null));
        Response early = buyerPost("/api/v1/orders/" + processing + "/return-requests", user,
                Map.of("reason_code", "DAMAGED", "lines", List.of(
                        Map.of("order_line_id", lineIds(processing).get(0).toString(), "quantity", 2))));
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.body().path("code").asText()).isEqualTo("REFUND_REQUIRES_ALL_ITEMS_DELIVERED");
    }

    @Test
    void refundFailureOpensPayoutRemediationAndExternalSettlementConverges() throws Exception {
        Placed placed = completed();
        long orderId = placed.orderId();
        String operationId = returnAC(placed).body().path("operation_id").asText();
        awaitCase(orderId, "WAITING_INVENTORY_SCOPE_INSPECTION");
        inspectionCompleted(orderId, new long[][]{{placed.lines().get(0), 2, 2}, {placed.lines().get(2), 3, 3}});
        await("refund bound", () -> jdbc.queryForObject(
                "SELECT refund_transaction_id FROM order_refund_cases WHERE order_id = ?", String.class, orderId) != null);
        refundEvent(orderId, "refund.failed", Map.of("error_code", "INSUFFICIENT_BALANCE"));
        awaitCase(orderId, "FAILED");
        assertThat(order(orderId).get("payment_status")).isEqualTo("REFUND_FAILED");
        assertThat(orderStatus(orderId)).isEqualTo("RETURN_REQUESTED");
        await("closed rejected", () -> fakes.callsFor(orderId).contains("inventory.closeScope:" + orderId + ":REJECTED"));
        await("manual review", () -> "MANUAL_REVIEW_REQUIRED".equals(operationStatus(operationId)));
        // The failure names the real cause (a refund that needs Finance), not an order-state conflict.
        JsonNode manual = buyerGet("/api/v1/order-operations/" + operationId, placed.user()).body().path("failure");
        assertThat(manual.path("code").asText()).isEqualTo("REFUND_MANUAL_REVIEW_REQUIRED");
        assertThat(manual.path("retryable").asBoolean()).isFalse();

        JsonNode reconciliation = reconciliationCase(orderId);
        JsonNode obligation = obligation(reconciliation, "CUSTOMER_PAYOUT_REMEDIATION");
        long locked = jdbc.queryForObject("SELECT refund_amount_locked_minor FROM order_refund_cases WHERE order_id = ?",
                Long.class, orderId);
        // Partial settlement is rejected; the exact amount converges the lane without a provider fact.
        Map<String, Object> partial = externalDecision(reconciliation, obligation, placed.user(), locked - 1);
        assertThat(decide(orderId, partial).status()).isEqualTo(422);
        Response ok = decide(orderId, externalDecision(reconciliation, obligation, placed.user(), locked));
        assertThat(ok.status()).as(String.valueOf(ok.body())).isEqualTo(202);
        awaitStatus(orderId, "REFUNDED");
        assertThat(jdbc.queryForObject("SELECT case_status FROM order_refund_cases WHERE order_id = ?", String.class, orderId))
                .isEqualTo("EXTERNALLY_REMEDIATED");
        assertThat(order(orderId).get("payment_status")).isEqualTo("REFUND_FAILED");
        assertThat(notificationTemplates(orderId)).doesNotContain("ORDER_REFUNDED");
        assertThat(operationStatus(operationId)).isEqualTo("SUCCEEDED");
    }

    @Test
    void reversalAfterSuccessReopensManualVerificationWithoutSecondRefund() throws Exception {
        Placed placed = completed();
        long orderId = placed.orderId();
        returnAC(placed);
        awaitCase(orderId, "WAITING_INVENTORY_SCOPE_INSPECTION");
        inspectionCompleted(orderId, new long[][]{{placed.lines().get(0), 2, 2}, {placed.lines().get(2), 3, 3}});
        await("refund bound", () -> jdbc.queryForObject(
                "SELECT refund_transaction_id FROM order_refund_cases WHERE order_id = ?", String.class, orderId) != null);
        refundEvent(orderId, "refund.succeeded", Map.of());
        awaitStatus(orderId, "REFUNDED");
        await("scope closed", () -> "COMPLETED".equals(finalization(orderId)));
        temporal.getWorkflowClient().newUntypedWorkflowStub(WorkflowNames.buyerRefund(orderId))
                .getResult(15, TimeUnit.SECONDS, Void.class);
        refundEvent(orderId, "refund.reversed", Map.of("reason_code", "REFUND_REVERSED_BY_PROVIDER"));
        awaitStatus(orderId, "RETURN_REQUESTED");
        awaitCase(orderId, "MANUAL_VERIFICATION_REQUIRED");
        assertThat(order(orderId).get("payment_status")).isEqualTo("REFUND_FAILED");
        assertThat(obligation(reconciliationCase(orderId), "REFUND_REVERSAL")).isNotNull();
        assertThat(fakes.callsFor(orderId).stream().filter(c -> c.startsWith("payment.refund")).count()).isEqualTo(1);
        // The frozen close reason survives the reversal; Inventory is not reopened.
        assertThat(fakes.callsFor(orderId).stream().filter(c -> c.startsWith("inventory.closeScope")).toList())
                .containsExactly("inventory.closeScope:" + orderId + ":REFUNDED");
    }

    @Test
    void reversalDeliveredDuringSuccessActivityIsAppliedBeforeWorkflowCloses() throws Exception {
        Placed placed = completed();
        long orderId = placed.orderId();
        assertThat(returnAC(placed).status()).isEqualTo(202);
        awaitCase(orderId, "WAITING_INVENTORY_SCOPE_INSPECTION");
        inspectionCompleted(orderId, new long[][]{{placed.lines().get(0), 2, 2}, {placed.lines().get(2), 3, 3}});
        await("refund bound", () -> jdbc.queryForObject(
                "SELECT refund_transaction_id FROM order_refund_cases WHERE order_id = ?", String.class, orderId) != null);

        CountDownLatch successCommitted = new CountDownLatch(1);
        CountDownLatch completeSuccessActivity = new CountDownLatch(1);
        doAnswer(invocation -> {
            String result = (String) invocation.callRealMethod();
            successCommitted.countDown();
            if (!completeSuccessActivity.await(25, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test did not release the refund success activity");
            }
            return result;
        }).when(orderActivities).applyRefundResult(eq(orderId),
                argThat(attributes -> attributes != null && "SUCCEEDED".equals(attributes.get("result"))));

        String workflowId = WorkflowNames.buyerRefund(orderId);
        try {
            refundEvent(orderId, "refund.succeeded", Map.of());
            assertThat(successCommitted.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(orderStatus(orderId)).isEqualTo("REFUNDED");

            refundEvent(orderId, "refund.reversed", Map.of("reason_code", "REFUND_REVERSED_BY_PROVIDER"));
            await("reversal signalled to the open refund workflow", () -> jdbc.queryForObject("""
                    SELECT count(*) FROM order_workflow_messages
                    WHERE order_id = ? AND target_workflow_id = ? AND payload->>'result' = 'REVERSED'
                      AND status = 'DELIVERED' AND delivery_outcome = 'SIGNALLED'
                    """, Integer.class, orderId, workflowId) == 1);
        } finally {
            completeSuccessActivity.countDown();
        }

        temporal.getWorkflowClient().newUntypedWorkflowStub(workflowId).getResult(15, TimeUnit.SECONDS, Void.class);
        assertThat(orderStatus(orderId)).isEqualTo("RETURN_REQUESTED");
        assertThat(order(orderId).get("payment_status")).isEqualTo("REFUND_FAILED");
        assertThat(jdbc.queryForObject("SELECT case_status FROM order_refund_cases WHERE order_id = ?",
                String.class, orderId)).isEqualTo("MANUAL_VERIFICATION_REQUIRED");
        assertThat(obligation(reconciliationCase(orderId), "REFUND_REVERSAL")).isNotNull();
        assertThat(fakes.callsFor(orderId).stream().filter(call -> call.startsWith("payment.refund"))).hasSize(1);
    }

    @Test
    void inspectionTimeoutNeedsAnAuditedNoPayoutDecision() throws Exception {
        Placed placed = completed();
        long orderId = placed.orderId();
        String operationId = returnAC(placed).body().path("operation_id").asText();
        awaitCase(orderId, "WAITING_INVENTORY_SCOPE_INSPECTION");
        temporal.sleep(Duration.ofDays(31));
        awaitCase(orderId, "MANUAL_VERIFICATION_REQUIRED");
        assertThat(fakes.callsFor(orderId)).noneMatch(c -> c.startsWith("payment.refund") || c.contains("closeIncomplete"));

        String scopeId = "scope-" + orderId;
        String requestId = jdbc.queryForObject("SELECT refund_request_id FROM order_refund_cases WHERE order_id = ?",
                String.class, orderId);
        fakes.scope(scopeId, Long.toString(orderId), requestId, "IN_PROGRESS",
                new long[][]{{placed.lines().get(0), 2, 1, 1, 0}, {placed.lines().get(2), 3, 0, 0, 0}});
        JsonNode reconciliation = reconciliationCase(orderId);
        JsonNode obligation = obligation(reconciliation, "RETURN_MANUAL_VERIFICATION");
        Map<String, Object> body = base(reconciliation, obligation, "CLOSE_RETURN_WITHOUT_REFUND", "RETURN_WINDOW_EXPIRED");
        body.put("return_closure", Map.of("refund_request_id", requestId, "refund_scope_id", scopeId,
                "reason", "RETURN_WINDOW_EXPIRED"));
        body.put("evidence", List.of(Map.of("source", "ORDER_CHECKPOINT",
                "reference", "refund-scope-query/order-" + orderId + "/" + scopeId + "/revision-4",
                "sha256", "b".repeat(64))));
        Response decided = decide(orderId, body);
        assertThat(decided.status()).as(String.valueOf(decided.body())).isEqualTo(202);
        awaitCase(orderId, "CLOSED_INCOMPLETE");
        assertThat(fakes.callsFor(orderId)).contains("inventory.closeIncomplete:" + orderId + ":RETURN_WINDOW_EXPIRED");
        assertThat(orderStatus(orderId)).isEqualTo("RETURN_REQUESTED");
        assertThat(order(orderId).get("payment_status")).isEqualTo("PAID");
        assertThat(operationStatus(operationId)).isEqualTo("SUCCEEDED");
        assertThat(fakes.callsFor(orderId)).noneMatch(c -> c.startsWith("payment.refund"));
        ContractSchemas.assertComponent(ContractSchemas.PUBLIC, "RefundCase",
                buyerGet("/api/v1/orders/" + orderId + "/refund-case", placed.user()).body());
        ContractSchemas.assertComponent(ContractSchemas.INTERNAL, "ReconciliationCase", reconciliationCase(orderId));
        // Late full completion is evidence only: it can never mint a payout.
        inspectionCompleted(orderId, new long[][]{{placed.lines().get(0), 2, 2}, {placed.lines().get(2), 3, 3}});
        assertThat(fakes.callsFor(orderId)).noneMatch(c -> c.startsWith("payment.refund"));
    }

    @Test
    void adminPreDispatchRefundRejectedThenAccepted() throws Exception {
        String user = UUID.randomUUID().toString();
        long orderId = paidOrder(Fixtures.submission(UUID.randomUUID().toString(), user, Fixtures.defaultLines(), 0, null));
        fakes.behaviour.put("releaseCommitted:" + orderId, "DISPATCHED");
        Response rejected = adminPost("/api/v1/admin/orders/" + orderId + "/pre-dispatch-refund-requests",
                Map.of("reason_code", "CUSTOMER_REQUEST"));
        assertThat(rejected.status()).isEqualTo(202);
        assertThat(rejected.raw().getResponse().getHeader("Location")).startsWith("/api/v1/admin/order-operations/");
        String rejectedOp = rejected.body().path("operation_id").asText();
        await("admin rejected", () -> "REJECTED".equals(operationStatus(rejectedOp)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund_cases WHERE order_id = ?", Long.class, orderId))
                .isZero();
        assertThat(orderStatus(orderId)).isEqualTo("PROCESSING");

        fakes.behaviour.remove("releaseCommitted:" + orderId);
        Response accepted = adminPost("/api/v1/admin/orders/" + orderId + "/pre-dispatch-refund-requests",
                Map.of("reason_code", "CUSTOMER_REQUEST"));
        String acceptedOp = accepted.body().path("operation_id").asText();
        awaitStatus(orderId, "RETURN_REQUESTED");
        await("refund bound", () -> jdbc.queryForObject(
                "SELECT refund_transaction_id FROM order_refund_cases WHERE order_id = ?", String.class, orderId) != null);
        long finalAmount = (Long) order(orderId).get("final_amount_minor");
        assertThat(fakes.callsFor(orderId)).contains("payment.refund:" + orderId + ":" + finalAmount);
        assertThat(finalization(orderId)).isEqualTo("NOT_REQUIRED");
        refundEvent(orderId, "refund.succeeded", Map.of());
        awaitStatus(orderId, "REFUNDED");
        await("admin op succeeded", () -> "SUCCEEDED".equals(operationStatus(acceptedOp)));
        assertThat(buyerGet("/api/v1/admin/order-operations/" + acceptedOp, user).status()).isEqualTo(403);
    }

    @Test
    void scopeFinalizationRecoversAfterRetryExhaustion() throws Exception {
        Placed placed = completed();
        long orderId = placed.orderId();
        String operationId = returnAC(placed).body().path("operation_id").asText();
        awaitCase(orderId, "WAITING_INVENTORY_SCOPE_INSPECTION");
        inspectionCompleted(orderId, new long[][]{{placed.lines().get(0), 2, 2}, {placed.lines().get(2), 3, 3}});
        await("refund bound", () -> jdbc.queryForObject(
                "SELECT refund_transaction_id FROM order_refund_cases WHERE order_id = ?", String.class, orderId) != null);
        fakes.transportFailures.put("close:" + orderId, 4);
        refundEvent(orderId, "refund.succeeded", Map.of());
        awaitStatus(orderId, "REFUNDED");
        for (int i = 0; i < 20 && !"RECOVERY_REQUIRED".equals(finalization(orderId)); i++) {
            temporal.sleep(Duration.ofSeconds(30));
            Thread.sleep(200);
        }
        assertThat(finalization(orderId)).isEqualTo("RECOVERY_REQUIRED");
        assertThat(operationStatus(operationId)).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(buyerGet("/api/v1/order-operations/" + operationId, placed.user()).body()
                .path("failure").path("code").asText()).isEqualTo("REFUND_MANUAL_REVIEW_REQUIRED");
        assertThat(order(orderId).get("status")).isEqualTo("REFUNDED");
        for (int i = 0; i < 40 && !"COMPLETED".equals(finalization(orderId)); i++) {
            temporal.sleep(Duration.ofMinutes(1));
            Thread.sleep(200);
        }
        assertThat(finalization(orderId)).isEqualTo("COMPLETED");
        await("operation succeeded", () -> "SUCCEEDED".equals(operationStatus(operationId)));
        assertThat(fakes.callsFor(orderId).stream().filter(c -> c.startsWith("payment.refund")).count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ reconciliation helpers

    private JsonNode reconciliationCase(long orderId) throws Exception {
        Response response = call(MockMvcRequestBuilders.get("/internal/v1/orders/" + orderId
                + "/financial-reconciliation-case").header("X-Service-Identity", "finance-ops"));
        assertThat(response.status()).isEqualTo(200);
        return response.body();
    }

    private JsonNode obligation(JsonNode reconciliation, String type) {
        for (JsonNode obligation : reconciliation.path("obligations")) {
            if (type.equals(obligation.path("obligation_type").asText())) {
                return obligation;
            }
        }
        throw new AssertionError("No obligation " + type + " in " + reconciliation);
    }

    private Map<String, Object> base(JsonNode reconciliation, JsonNode obligation, String resolution, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("case_id", reconciliation.path("case_id").asText());
        body.put("obligation_id", obligation.path("obligation_id").asText());
        body.put("expected_case_revision", reconciliation.path("revision").asLong());
        body.put("decision_id", UUID.randomUUID().toString());
        body.put("resolution_type", resolution);
        body.put("reason_code", reason);
        return body;
    }

    private Map<String, Object> externalDecision(JsonNode reconciliation, JsonNode obligation, String user, long amount) {
        Map<String, Object> body = base(reconciliation, obligation, "CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION",
                "BANK_TRANSFER_SETTLED");
        body.put("accounting_case_reference", "ACC-" + UUID.randomUUID());
        body.put("external_settlement", Map.of("amount", Map.of("amount_minor", amount, "currency", "VND"),
                "customer_user_id", user, "settled_at", Instant.now().toString(),
                "settlement_reference", "BANK-TRF-1"));
        body.put("evidence", List.of(Map.of("source", "PAYMENT_RECONCILIATION", "reference", "settlement/BANK-TRF-1",
                "sha256", "c".repeat(64))));
        return body;
    }

    private Response decide(long orderId, Map<String, Object> body) throws Exception {
        return call(MockMvcRequestBuilders.post("/internal/v1/orders/" + orderId + "/financial-reconciliation-decisions")
                .header("X-Service-Identity", "finance-ops").header("X-Actor-Id", UUID.randomUUID().toString())
                .header("Idempotency-Key", (String) body.get("decision_id")).contentType("application/json")
                .content(mapper.writeValueAsString(body)));
    }
}
