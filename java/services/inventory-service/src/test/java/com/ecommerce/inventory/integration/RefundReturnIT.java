package com.ecommerce.inventory.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Refund Scope lifecycle, return receipt, inspection, quarantine disposition and closure. */
class RefundReturnIT extends AbstractIntegrationTest {

    private static final String WF = "/internal/inventory/v1";
    private static final String ADMIN = "/api/v1/admin/inventory";

    private String wh;

    private void dispatchedOrder(String orderId, long quantity) throws Exception {
        reserve(orderId, "l1", wh, "sku-a", String.valueOf(quantity), "l2", wh, "sku-b", "1");
        workflow(HttpMethod.POST, WF + "/reservations/" + orderId + "/commit", Map.of("payment_reference", "p"), key());
        dispatch(orderId, "l1", wh, "sku-a", quantity);
    }

    private Response register(String orderId, String refundRequestId, String lineId, String sku, long qty)
            throws Exception {
        return workflow(HttpMethod.POST, WF + "/refund-scopes", Map.of("order_id", orderId,
                "refund_request_id", refundRequestId, "lines", List.of(Map.of("order_line_id", lineId, "sku_id", sku,
                        "expected_quantity", qty))), key());
    }

    private Response returnReceipt(String scopeId, String sku, long qty, String receiptId) throws Exception {
        return admin(HttpMethod.POST, ADMIN + "/return-receipts", Map.of("refund_scope_id", scopeId,
                "order_line_id", "l1", "actual_sku_id", sku, "warehouse_id", wh, "quantity", qty,
                "return_receipt_id", receiptId, "return_receipt_line_id", "1", "package_reference", "pkg-" + receiptId,
                "received_at", "2026-10-05T09:00:00Z", "evidence_reference", "evidence://pkg"), key());
    }

    private Response inspect(String receiptId, long qty, String result) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("inspection_id", "insp-" + UUID.randomUUID(),
                "return_receipt_id", receiptId, "return_receipt_line_id", "1", "warehouse_id", wh, "quantity", qty,
                "result", result, "checklist_reference", "check-1", "evidence_reference", "evidence://insp"));
        if ("FAIL".equals(result)) {
            body.put("fail_reason", "DAMAGED");
        }
        return admin(HttpMethod.POST, ADMIN + "/inspections", body, key());
    }

    @Test
    void selectedDispatchedFullQuantityScopeThroughInspectionCompletionAndClose() throws Exception {
        wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 10);
        receive(wh, "sku-b", 5);
        dispatchedOrder("order-1", 2);

        assertThat(register("order-1", "rr-1", "l2", "sku-b", 1).body().path("code").asText())
                .isEqualTo("REFUND_SCOPE_LINE_NOT_DISPATCHED");
        assertThat(register("order-1", "rr-1", "l1", "sku-a", 1).body().path("code").asText())
                .isEqualTo("REFUND_SCOPE_LINE_INVALID");
        Response registered = register("order-1", "rr-1", "l1", "sku-a", 2);
        assertThat(registered.body().path("outcome").asText()).isEqualTo("REGISTERED");
        String scopeId = registered.body().path("refund_scope_id").asText();
        assertThat(register("order-1", "rr-1", "l1", "sku-a", 2).body().path("refund_scope_id").asText())
                .isEqualTo(scopeId);
        assertThat(register("order-1", "rr-2", "l1", "sku-a", 2).body().path("code").asText())
                .isEqualTo("REFUND_SCOPE_ALREADY_EXISTS");
        assertThat(workflow(HttpMethod.POST, WF + "/refund-scopes/" + scopeId + "/close-incomplete",
                Map.of("reason", "PACKAGE_LOST"), key()).body().path("code").asText())
                .isEqualTo("REFUND_SCOPE_STATE_CONFLICT");

        assertThat(returnReceipt(scopeId, "sku-z", 1, "ret-x").body().path("code").asText())
                .isEqualTo("RETURN_RECEIPT_REJECTED");
        assertThat(returnReceipt(scopeId, "sku-a", 3, "ret-y").body().path("code").asText())
                .isEqualTo("RETURN_RECEIPT_REJECTED");
        assertThat(returnReceipt(scopeId, "sku-a", 1, "ret-1").status()).isEqualTo(200);
        JsonNode pos = position(wh, "sku-a");
        assertThat(pos.path("quarantine").asLong()).isEqualTo(1);

        JsonNode queue = admin(HttpMethod.GET, ADMIN + "/inspection-queue?warehouse_id=" + wh, null, null).body();
        assertThat(queue.path("items").get(0).path("uninspected_quantity").asLong()).isEqualTo(1);
        assertThat(queue.path("items").get(0).path("order_id").asText()).isEqualTo("order-1");

        assertThat(inspect("ret-1", 2, "PASS").body().path("code").asText()).isEqualTo("INSPECTION_REJECTED");
        assertThat(inspect("ret-1", 1, "PASS").status()).isEqualTo(200);
        assertThat(returnReceipt(scopeId, "sku-a", 1, "ret-2").status()).isEqualTo(200);
        assertThat(inspect("ret-2", 1, "FAIL").status()).isEqualTo(200);

        JsonNode scope = workflow(HttpMethod.GET, WF + "/refund-scopes/" + scopeId, null, null).body();
        assertThat(scope.path("state").asText()).isEqualTo("INSPECTION_COMPLETED");
        assertThat(scope.path("lines").get(0).path("passed_quantity").asLong()).isEqualTo(1);
        assertThat(scope.path("lines").get(0).path("failed_quantity").asLong()).isEqualTo(1);
        List<String> completions = jdbc.queryForList("SELECT payload::text FROM outbox_event WHERE event_type = "
                + "'com.ecommerce.inventory.refund.all-items-inspection-completed.v1'", String.class);
        assertThat(completions).hasSize(1);
        JsonNode completion = objectMapper.readTree(completions.get(0));
        assertThat(completion.path("aggregaterevision").asLong()).isEqualTo(scope.path("revision").asLong());

        Response close = workflow(HttpMethod.POST, WF + "/refund-scopes/" + scopeId + "/close",
                Map.of("reason", "REFUNDED"), key());
        assertThat(close.body().path("outcome").asText()).isEqualTo("CLOSED");
        assertThat(returnReceipt(scopeId, "sku-a", 1, "ret-3").body().path("code").asText())
                .isEqualTo("REFUND_SCOPE_STATE_CONFLICT");
        assertThat(register("order-1", "rr-3", "l1", "sku-a", 2).body().path("code").asText())
                .isEqualTo("REFUND_SCOPE_ALREADY_EXISTS");

        pos = position(wh, "sku-a");
        assertThat(pos.path("on_hand").asLong()).isEqualTo(10);
        assertThat(pos.path("available_to_sell").asLong()).isEqualTo(9);
        assertThat(pos.path("quarantine").asLong()).isEqualTo(1);
    }

    @Test
    void failedStockHoldsDisposesAndReturnsToQuarantineOnly() throws Exception {
        wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 4);
        receive(wh, "sku-b", 1);
        dispatchedOrder("order-1", 2);
        String scopeId = register("order-1", "rr-1", "l1", "sku-a", 2).body().path("refund_scope_id").asText();
        returnReceipt(scopeId, "sku-a", 2, "ret-1");
        Response failed = inspect("ret-1", 2, "FAIL");
        assertThat(failed.status()).isEqualTo(200);
        String inspectionId = jdbc.queryForObject("SELECT inspection_id FROM inspection_result", String.class);

        Response hold = admin(HttpMethod.POST, ADMIN + "/quarantine-dispositions", Map.of("disposition_id", "dsp-1",
                "disposition_line_id", "1", "inspection_id", inspectionId, "warehouse_id", wh, "sku_id", "sku-a",
                "quantity", 2, "disposition", "HOLD", "reason", "SUPPLIER_CLAIM", "evidence_reference", "e://hold"),
                key());
        assertThat(hold.status()).isEqualTo(200);
        assertThat(hold.body().has("movement_id")).isFalse();
        JsonNode items = admin(HttpMethod.GET, ADMIN + "/quarantine-items?warehouse_id=" + wh, null, null).body();
        assertThat(items.path("items").get(0).path("disposable_quantity").asLong()).isEqualTo(2);
        assertThat(items.path("items").get(0).path("latest_hold_reason").asText()).isEqualTo("SUPPLIER_CLAIM");

        Response noDeparture = admin(HttpMethod.POST, ADMIN + "/quarantine-dispositions", Map.of("disposition_id",
                "dsp-2", "disposition_line_id", "1", "inspection_id", inspectionId, "warehouse_id", wh, "sku_id",
                "sku-a", "quantity", 2, "disposition", "DISPOSE", "reason", "UNSAFE", "evidence_reference", "e://d"),
                key());
        assertThat(noDeparture.status()).isEqualTo(400);

        Map<String, Object> dispose = new HashMap<>(Map.of("disposition_id", "dsp-2", "disposition_line_id", "1",
                "inspection_id", inspectionId, "warehouse_id", wh, "sku_id", "sku-a", "quantity", 2,
                "disposition", "DISPOSE", "reason", "UNSAFE", "evidence_reference", "e://d"));
        dispose.put("physical_departure_reference", "truck-1");
        dispose.put("departed_at", "2026-10-05T10:00:00Z");
        assertThat(admin(HttpMethod.POST, ADMIN + "/quarantine-dispositions", dispose, key()).status()).isEqualTo(200);
        assertThat(position(wh, "sku-a").path("quarantine").asLong()).isZero();
        assertThat(admin(HttpMethod.GET, ADMIN + "/quarantine-items?warehouse_id=" + wh, null, null).body()
                .path("items")).isEmpty();

        Map<String, Object> reversal = Map.of("disposition_line_id", "1", "reversal_sequence", 1, "quantity", 1,
                "reason", "DISPOSAL_ABORTED", "reason_note", "truck turned back",
                "physically_returned_at", "2026-10-05T11:00:00Z", "physical_return_reference", "ret-truck-1",
                "evidence_reference", "e://back");
        assertThat(admin(HttpMethod.POST, ADMIN + "/quarantine-dispositions/dsp-2/reversals", reversal, key())
                .status()).isEqualTo(200);
        Response holdReversal = admin(HttpMethod.POST, ADMIN + "/quarantine-dispositions/dsp-1/reversals", reversal,
                key());
        assertThat(holdReversal.body().path("code").asText()).isEqualTo("DISPOSITION_REJECTED");
        JsonNode pos = position(wh, "sku-a");
        assertThat(pos.path("quarantine").asLong()).isEqualTo(1);
        assertThat(pos.path("available_to_sell").asLong()).isEqualTo(2);
    }

    @Test
    void cancelAndIncompleteCloseKeepTheLifetimeIdentity() throws Exception {
        wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 10);
        receive(wh, "sku-b", 5);
        dispatchedOrder("order-c", 1);
        String cancelled = register("order-c", "rr-c", "l1", "sku-a", 1).body().path("refund_scope_id").asText();
        Response cancel = workflow(HttpMethod.POST, WF + "/refund-scopes/" + cancelled + "/cancel", null, key());
        assertThat(cancel.body().path("outcome").asText()).isEqualTo("CANCELLED");
        assertThat(register("order-c", "rr-c2", "l1", "sku-a", 1).body().path("code").asText())
                .isEqualTo("REFUND_SCOPE_ALREADY_EXISTS");

        dispatchedOrder("order-i", 3);
        String scopeId = register("order-i", "rr-i", "l1", "sku-a", 3).body().path("refund_scope_id").asText();
        returnReceipt(scopeId, "sku-a", 1, "ret-i");
        Response pending = workflow(HttpMethod.POST, WF + "/refund-scopes/" + scopeId + "/close-incomplete",
                Map.of("reason", "PACKAGE_LOST"), key());
        assertThat(pending.body().path("code").asText()).isEqualTo("REFUND_SCOPE_STATE_CONFLICT");
        inspect("ret-i", 1, "PASS");
        Response closed = workflow(HttpMethod.POST, WF + "/refund-scopes/" + scopeId + "/close-incomplete",
                Map.of("reason", "PACKAGE_LOST"), key());
        assertThat(closed.body().path("outcome").asText()).isEqualTo("CLOSED_INCOMPLETE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type LIKE '%inspection-completed%'",
                Integer.class)).isZero();
        JsonNode admin = admin(HttpMethod.GET, ADMIN + "/refund-scopes?order_id=order-i", null, null).body();
        assertThat(admin.path("state").asText()).isEqualTo("CLOSED_INCOMPLETE");
    }
}
