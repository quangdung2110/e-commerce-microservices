package com.ecommerce.order.integration;

import com.ecommerce.order.support.AbstractIntegrationTest;
import com.ecommerce.order.support.Fixtures;
import com.ecommerce.order.web.dto.CheckoutDtos.CheckoutOrderSubmissionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** WF-ORDER-004/005/006/006a/009/011 end to end against PostgreSQL and in-memory Temporal. */
class CheckoutFlowIT extends AbstractIntegrationTest {

    private static String user() {
        return UUID.randomUUID().toString();
    }

    @Test
    void checkoutPaymentFulfilmentAndCompletion() throws Exception {
        String user = user();
        CheckoutOrderSubmissionRequest request = Fixtures.submission(UUID.randomUUID().toString(), user,
                Fixtures.defaultLines(), 200_000, "promo-" + UUID.randomUUID());
        Response created = submit(request);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.raw().getResponse().getHeader("Cache-Control")).isEqualTo("private, no-store");
        JsonNode payment = created.body().path("payment");
        assertThat(payment.path("usable").asBoolean()).isTrue();
        assertThat(payment.path("client_secret").asText()).startsWith("cs_test_");
        assertThat(created.body().path("order_status").asText()).isEqualTo("PENDING_PAYMENT");
        long orderId = created.body().path("order_id").asLong();

        // Replay with the same key/body returns 200 and the same order; another body is 409.
        Response replay = submit(request);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().path("order_id").asLong()).isEqualTo(orderId);
        Response conflict = submit(Fixtures.submission(request.checkoutToken(), user, Fixtures.defaultLines(), 100_000,
                "promo-other"));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body().path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        Map<String, Object> order = order(orderId);
        assertThat(order.get("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(order.get("payment_deadline_locked_at")).isNotNull();
        assertThat(order.get("order_code")).isEqualTo("ORD-" + orderId);
        // Discount shares sum to the order discount and net = gross - share.
        Long shares = jdbc.queryForObject("SELECT SUM(discount_share_minor) FROM order_item_lines WHERE order_id = ?",
                Long.class, orderId);
        assertThat(shares).isEqualTo(200_000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ?", Long.class, orderId))
                .isEqualTo(2L);

        paymentSucceeded(orderId);
        awaitStatus(orderId, "PROCESSING");
        assertThat(fakes.callsFor(orderId)).anyMatch(c -> c.startsWith("inventory.commit"))
                .anyMatch(c -> c.startsWith("promotion.confirm") && c.contains("REDEEM_FINAL:" + orderId + ":"));
        List<JsonNode> paid = outbox(orderId, "com.ecommerce.order.paid.v1");
        assertThat(paid).hasSize(1);
        assertThat(paid.get(0).path("sequence").asLong()).isEqualTo(1);
        assertThat(paid.get(0).path("data").path("sku_ids")).hasSize(3);

        dispatchAll(orderId);
        Response detail = buyerGet("/api/v1/orders/" + orderId, user);
        assertThat(detail.body().path("buyer_tab").asText()).isEqualTo("TO_RECEIVE");
        assertThat(orderStatus(orderId)).isEqualTo("PROCESSING");
        deliverAll(orderId);
        awaitStatus(orderId, "COMPLETED");
        List<JsonNode> completed = outbox(orderId, "com.ecommerce.order.completed.v1");
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).path("sequence").asLong()).isEqualTo(2);
        assertThat(completed.get(0).path("data").path("items").toString()).contains("Đỏ / M");
        assertThat(notificationTemplates(orderId)).containsExactly("ORDER_CONFIRMED", "ORDER_COMPLETED");
        validateAgainstContracts(orderId);
    }

    @Test
    void promotionReserveFailureReturnsRecalculationAndCleansUp() throws Exception {
        String user = user();
        fakes.behaviour.put("promotion:" + user, "BUDGET_EXHAUSTED");
        String promotion = "promo-" + UUID.randomUUID();
        CheckoutOrderSubmissionRequest request = Fixtures.submission(UUID.randomUUID().toString(), user,
                Fixtures.defaultLines(), 50_000, promotion);
        Response response = submit(request);
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().path("code").asText()).isEqualTo("PROMOTION_RECALCULATION_NEEDED");
        assertThat(response.body().path("failed_promotion_id").asText()).isEqualTo(promotion);
        assertThat(response.body().path("failed_reason").asText()).isEqualTo("BUDGET_EXHAUSTED");
        Response status = call(MockMvcRequestBuilders.get("/internal/v1/checkout-order-submissions/" + request.checkoutToken())
                .header("X-Service-Identity", "checkout-service"));
        assertThat(status.body().path("state").asText()).isEqualTo("RECALCULATION_REQUIRED");
        long orderId = status.body().path("order_id").asLong();
        awaitStatus(orderId, "CANCELLED");
        assertThat(fakes.callsFor(orderId)).contains("inventory.release:" + orderId + ":PRE_PAYMENT_SAGA_FAILED");
        assertThat(order(orderId).get("cancel_reason")).isEqualTo("PRE_PAYMENT_SAGA_FAILED");
    }

    @Test
    void inventoryRejectAllFailsSubmission() throws Exception {
        String token = UUID.randomUUID().toString();
        fakes.behaviour.put("reserve:plan-" + token, "REJECT_ALL");
        Response response = submit(Fixtures.submission(token, user(), Fixtures.defaultLines(), 0, null));
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().path("code").asText()).isEqualTo("INVENTORY_RESERVATION_REJECTED");
        long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE checkout_token = ?", Long.class, token);
        awaitStatus(orderId, "CANCELLED");
        String template = jdbc.queryForObject("""
                SELECT payload->'data'->'template_data'->>'reason' FROM order_notification_commands
                WHERE order_id = ? AND template_id = 'ORDER_CANCELLED'""", String.class, orderId);
        assertThat(template).isEqualTo("out_of_stock");
        assertThat(fakes.callsFor(orderId)).noneMatch(c -> c.startsWith("payment.initiate"));
    }

    @Test
    void paymentDeadlineCancelsAfterNonCaptureAndCleanup() throws Exception {
        long orderId = placeOrder(Fixtures.submission(Fixtures.defaultLines(), 0, null));
        temporal.sleep(Duration.ofMinutes(16));
        awaitStatus(orderId, "CANCELLED");
        assertThat(order(orderId).get("cancel_reason")).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(fakes.callsFor(orderId)).contains("payment.cancel:" + orderId + ":PAYMENT_TIMEOUT",
                "inventory.release:" + orderId + ":RESERVATION_EXPIRED");
        assertThat(notificationTemplates(orderId)).containsExactly("ORDER_CANCELLED");

        // A capture asserted after terminal non-capture is a reconciliation anomaly only.
        String txn = activeTxn(orderId);
        long amount = (Long) order(orderId).get("final_amount_minor");
        payments.handle(Long.toString(orderId), paymentEvent(orderId, "transaction.succeeded", txn, 1, amount, Map.of()));
        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM order_reconciliation_obligations
                WHERE order_id = ? AND obligation_type = 'CAPTURE_AFTER_TERMINAL_NON_CAPTURE'""", Long.class, orderId))
                .isEqualTo(1L);
        assertThat(outbox(orderId, "com.ecommerce.order.paid.v1")).isEmpty();
    }

    @Test
    void buyerCancellationIsIdempotentAndCompletesAfterCleanup() throws Exception {
        String user = user();
        long orderId = placeOrder(Fixtures.submission(UUID.randomUUID().toString(), user, Fixtures.defaultLines(), 0, null));
        String key = UUID.randomUUID().toString();
        Map<String, String> body = Map.of("reason_code", "USER_CANCEL");
        Response first = call(buyer(MockMvcRequestBuilders.post("/api/v1/orders/" + orderId + "/cancellation-requests"), user)
                .header("Idempotency-Key", key).contentType("application/json").content(mapper.writeValueAsString(body)));
        assertThat(first.status()).isEqualTo(202);
        assertThat(first.raw().getResponse().getHeader("Location")).startsWith("/api/v1/order-operations/");
        Response replay = call(buyer(MockMvcRequestBuilders.post("/api/v1/orders/" + orderId + "/cancellation-requests"), user)
                .header("Idempotency-Key", key).contentType("application/json").content(mapper.writeValueAsString(body)));
        assertThat(replay.body().path("operation_id")).isEqualTo(first.body().path("operation_id"));
        Response stranger = call(buyer(MockMvcRequestBuilders.post("/api/v1/orders/" + orderId + "/cancellation-requests"),
                user()).header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content(mapper.writeValueAsString(body)));
        assertThat(stranger.status()).isEqualTo(404);
        awaitStatus(orderId, "CANCELLED");
        String operationId = first.body().path("operation_id").asText();
        await("operation succeeded", () -> "SUCCEEDED".equals(operationStatus(operationId)));
        assertThat(fakes.callsFor(orderId)).contains("payment.cancel:" + orderId + ":BUYER_CANCELLED",
                "inventory.release:" + orderId + ":BUYER_CANCELLED");
        Response operation = buyerGet("/api/v1/order-operations/" + operationId, user);
        assertThat(operation.body().path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void captureWinsCancellationRace() throws Exception {
        String user = user();
        long orderId = placeOrder(Fixtures.submission(UUID.randomUUID().toString(), user, Fixtures.defaultLines(), 0, null));
        fakes.behaviour.put("cancel:" + orderId, "ALREADY_SUCCEEDED");
        Response cancel = buyerPost("/api/v1/orders/" + orderId + "/cancellation-requests", user,
                Map.of("reason_code", "USER_CANCEL"));
        assertThat(cancel.status()).isEqualTo(202);
        awaitStatus(orderId, "PROCESSING");
        String operationId = cancel.body().path("operation_id").asText();
        await("cancellation rejected", () -> "REJECTED".equals(operationStatus(operationId)));
        assertThat(outbox(orderId, "com.ecommerce.order.paid.v1")).hasSize(1);
        assertThat(fakes.callsFor(orderId)).noneMatch(c -> c.startsWith("inventory.release"));
        assertThat(order(orderId).get("cancel_reason")).isNull();
    }

    @Test
    void failedAttemptCanBeRetriedBeforeTheDeadline() throws Exception {
        String user = user();
        long orderId = placeOrder(Fixtures.submission(UUID.randomUUID().toString(), user, Fixtures.defaultLines(), 0, null));
        String firstTxn = activeTxn(orderId);
        long amount = (Long) order(orderId).get("final_amount_minor");
        fakes.setPaymentStatus(firstTxn, "FAILED");
        payments.handle(Long.toString(orderId), paymentEvent(orderId, "transaction.failed", firstTxn, 1, amount,
                Map.of("error_code", "CARD_DECLINED")));
        await("payment failed", () -> "FAILED".equals(order(orderId).get("payment_status")));
        String canRetry = jdbc.queryForObject("""
                SELECT payload->'data'->'template_data'->>'can_retry' FROM order_notification_commands
                WHERE order_id = ? AND template_id = 'ORDER_PAYMENT_FAILED'""", String.class, orderId);
        assertThat(canRetry).isEqualTo("true");
        assertThat(buyerGet("/api/v1/orders/" + orderId, user).body().path("available_actions")
                .path("can_retry_payment").asBoolean()).isTrue();

        Response retry = buyerPost("/api/v1/orders/" + orderId + "/payment-attempts", user,
                Map.of("return_url", "https://shop.example.test/orders/" + orderId + "/payment/complete"));
        assertThat(retry.status()).as(String.valueOf(retry.body())).isEqualTo(202);
        String operationId = retry.body().path("operation_id").asText();
        await("attempt operation", () -> "SUCCEEDED".equals(operationStatus(operationId)));
        Response attempt = buyerGet("/api/v1/orders/" + orderId + "/payment-attempts/2", user);
        assertThat(attempt.status()).isEqualTo(200);
        assertThat(attempt.body().path("status").asText()).isEqualTo("PENDING");
        assertThat(attempt.body().path("client_secret").asText()).startsWith("cs_test_");
        Response old = buyerGet("/api/v1/orders/" + orderId + "/payment-attempts/1", user);
        assertThat(old.body().path("status").asText()).isEqualTo("FAILED");
        assertThat(old.body().path("client_secret").isNull()).isTrue();

        paymentSucceeded(orderId);
        awaitStatus(orderId, "PROCESSING");
        assertThat(order(orderId).get("captured_payment_transaction_id")).isNotEqualTo(firstTxn);
    }

    @Test
    void duplicateAndStalePaymentFactsAreNoOps() throws Exception {
        long orderId = placeOrder(Fixtures.submission(Fixtures.defaultLines(), 0, null));
        String txn = activeTxn(orderId);
        long amount = (Long) order(orderId).get("final_amount_minor");
        String event = paymentEvent(orderId, "transaction.succeeded", txn, 1, amount, Map.of());
        payments.handle(Long.toString(orderId), event);
        payments.handle(Long.toString(orderId), event);
        awaitStatus(orderId, "PROCESSING");
        assertThat(outbox(orderId, "com.ecommerce.order.paid.v1")).hasSize(1);
        // Wrong amount on the same transaction is rejected to the inbox, never routed.
        String wrong = paymentEvent(orderId, "transaction.failed", txn, 1, amount + 1, Map.of("error_code", "X_Y"));
        payments.handle(Long.toString(orderId), wrong);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM order_consumed_events WHERE order_id = ? AND status = 'REJECTED'""",
                Long.class, orderId)).isEqualTo(1L);
    }

    @Test
    void deliveryProofBeforeDispatchIsParkedThenReplayed() throws Exception {
        long orderId = paidOrder(Fixtures.submission(Fixtures.defaultLines(), 0, null));
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT id, sku_id, quantity, allocated_warehouse_id FROM order_item_lines WHERE order_id = ? ORDER BY id",
                orderId);
        Map<String, Object> first = lines.get(0);
        long lineId = ((Number) first.get("id")).longValue();
        deliver(orderId, lineId, (String) first.get("allocated_warehouse_id"), "proof-early-" + orderId);
        assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM order_item_lines WHERE id = ?", String.class, lineId))
                .isEqualTo("PROCESSING");
        dispatch(orderId, lineId, (String) first.get("allocated_warehouse_id"), (String) first.get("sku_id"),
                ((Number) first.get("quantity")).intValue());
        assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM order_item_lines WHERE id = ?", String.class, lineId))
                .isEqualTo("DELIVERED");
        // The same proof cannot deliver another line; a mismatched warehouse is quarantined.
        Map<String, Object> second = lines.get(1);
        long secondId = ((Number) second.get("id")).longValue();
        dispatch(orderId, secondId, "WH-OTHER", (String) second.get("sku_id"), ((Number) second.get("quantity")).intValue());
        assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM order_item_lines WHERE id = ?", String.class, secondId))
                .isEqualTo("PROCESSING");
        assertThat(orderStatus(orderId)).isEqualTo("PROCESSING");
    }

    @Test
    void postCaptureRepairFailureEntersReconciliationAndManualFulfilment() throws Exception {
        String token = UUID.randomUUID().toString();
        long orderId = placeOrder(Fixtures.submission(token, user(), Fixtures.defaultLines(), 0, null));
        fakes.behaviour.put("commit:" + orderId, "NOT_RESERVED");
        paymentSucceeded(orderId);
        awaitStatus(orderId, "FINANCIAL_RECONCILIATION_REQUIRED");
        assertThat(outbox(orderId, "com.ecommerce.order.paid.v1")).hasSize(1);
        Response reconciliation = call(MockMvcRequestBuilders.get("/internal/v1/orders/" + orderId
                + "/financial-reconciliation-case").header("X-Service-Identity", "finance-ops"));
        assertThat(reconciliation.status()).isEqualTo(200);
        JsonNode obligation = reconciliation.body().path("obligations").get(0);
        assertThat(obligation.path("obligation_type").asText()).isEqualTo("POST_CAPTURE_INVENTORY_REPAIR");
        JsonNode evidence = obligation.path("evidence").get(0);
        String decision = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("case_id", reconciliation.body().path("case_id").asText(),
                "obligation_id", obligation.path("obligation_id").asText(),
                "expected_case_revision", reconciliation.body().path("revision").asLong(),
                "decision_id", decision, "resolution_type", "CLOSE_AS_MANUAL_FULFILLMENT",
                "reason_code", "WAREHOUSE_SHIPPED_MANUALLY",
                "evidence", List.of(Map.of("source", evidence.path("source").asText(),
                        "reference", evidence.path("reference").asText(), "sha256", evidence.path("sha256").asText())));
        Response decided = call(MockMvcRequestBuilders.post("/internal/v1/orders/" + orderId
                        + "/financial-reconciliation-decisions").header("X-Service-Identity", "finance-ops")
                .header("X-Actor-Id", UUID.randomUUID().toString()).header("Idempotency-Key", decision)
                .contentType("application/json").content(mapper.writeValueAsString(body)));
        assertThat(decided.status()).as(String.valueOf(decided.body())).isEqualTo(202);
        assertThat(decided.body().path("obligation_status").asText()).isEqualTo("CLOSED");
        assertThat(orderStatus(orderId)).isEqualTo("PROCESSING");
        // Same decision replays; a stale revision with a new decision ID is 409.
        Response replay = call(MockMvcRequestBuilders.post("/internal/v1/orders/" + orderId
                        + "/financial-reconciliation-decisions").header("X-Service-Identity", "finance-ops")
                .header("X-Actor-Id", UUID.randomUUID().toString()).header("Idempotency-Key", decision)
                .contentType("application/json").content(mapper.writeValueAsString(body)));
        assertThat(replay.status()).isEqualTo(202);
    }

    private void validateAgainstContracts(long orderId) throws Exception {
        Path contracts = Paths.get("..", "..", "..", "contracts").toAbsolutePath().normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(contracts),
                "contracts/ is present only inside the repository");
        String prefix = "https://contracts.ecommerce.local/";
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, builder ->
                builder.schemaMappers(m -> m.mapPrefix(prefix, contracts.toUri().toString())));
        Map<String, String> schemas = Map.of(
                "com.ecommerce.order.paid.v1", "events/order/v1/schemas/order-paid.schema.json",
                "com.ecommerce.order.completed.v1", "events/order/v1/schemas/order-completed.schema.json",
                "com.ecommerce.notification.requested.v1",
                "events/notification/v1/schemas/notification-requested.schema.json");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_type, payload::text AS payload FROM outbox_events WHERE aggregate_id = ?", Long.toString(orderId));
        assertThat(rows).hasSizeGreaterThanOrEqualTo(4);
        for (Map<String, Object> row : rows) {
            JsonNode event = mapper.readTree((String) row.get("payload"));
            JsonSchema schema = factory.getSchema(SchemaLocation.of(prefix + schemas.get((String) row.get("event_type"))));
            assertThat(schema.validate(event)).as(row.get("event_type") + " " + event).isEmpty();
        }
    }
}
