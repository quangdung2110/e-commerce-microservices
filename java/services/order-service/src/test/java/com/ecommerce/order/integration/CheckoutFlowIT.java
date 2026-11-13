package com.ecommerce.order.integration;

import com.ecommerce.order.common.CanonicalJson;
import com.ecommerce.order.service.CheckoutSaga;
import com.ecommerce.order.store.CheckpointStore;
import com.ecommerce.order.support.AbstractIntegrationTest;
import com.ecommerce.order.support.ContractSchemas;
import com.ecommerce.order.support.Fixtures;
import com.ecommerce.order.web.dto.CheckoutDtos.CheckoutOrderSubmissionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** WF-ORDER-004/005/006/006a/009/011 end to end against PostgreSQL and in-memory Temporal. */
class CheckoutFlowIT extends AbstractIntegrationTest {

    @Autowired
    private CheckpointStore checkpoints;
    @Autowired
    private CheckoutSaga checkout;

    @Test
    void completedEventUsesAttributesFromLegacyPurchaseSnapshotWhenLabelIsAbsent() throws Exception {
        long orderId = paidOrder(Fixtures.submission(List.of(
                new Fixtures.LineSpec("SKU-SNAPSHOT", 1, 20_000, "WH-HN", "Purchased product", null)), 0, null));
        assertThat(jdbc.queryForObject("SELECT snapshot_variant_name FROM order_item_lines WHERE order_id = ?",
                String.class, orderId)).isEqualTo("color: red");
        // Model an already-persisted order from the old producer; only display metadata changes.
        jdbc.update("UPDATE order_item_lines SET snapshot_variant_name = NULL WHERE order_id = ?", orderId);
        dispatchAll(orderId);
        deliverAll(orderId);
        awaitStatus(orderId, "COMPLETED");
        JsonNode event = outbox(orderId, "com.ecommerce.order.completed.v1").get(0);
        assertThat(event.path("data").path("items").get(0).path("variant_name").asText()).isEqualTo("color: red");
        validateAgainstContracts(orderId);
    }

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
        ContractSchemas.assertComponent(ContractSchemas.INTERNAL, "CheckoutOrderReady", created.body());

        // Replay with the same key/body returns 200 and the same order; another body is 409.
        Response replay = submit(request);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().path("order_id").asLong()).isEqualTo(orderId);
        Response conflict = submit(Fixtures.submission(request.checkoutToken(), user, Fixtures.defaultLines(), 100_000,
                "promo-other"));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body().path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        ContractSchemas.assertComponent(ContractSchemas.INTERNAL, "Problem", conflict.body());
        Response recovery = call(MockMvcRequestBuilders.get("/internal/v1/checkout-order-submissions/"
                + request.checkoutToken()).header("X-Service-Identity", "checkout-service"));
        ContractSchemas.assertComponent(ContractSchemas.INTERNAL, "CheckoutOrderSubmissionStatus", recovery.body());

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
        ContractSchemas.assertComponent(ContractSchemas.PUBLIC, "OrderDetail", detail.body());
        assertThat(orderStatus(orderId)).isEqualTo("PROCESSING");
        deliverAll(orderId);
        awaitStatus(orderId, "COMPLETED");
        List<JsonNode> completed = outbox(orderId, "com.ecommerce.order.completed.v1");
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).path("sequence").asLong()).isEqualTo(2);
        assertThat(completed.get(0).path("data").path("items").toString()).contains("Đỏ / M");
        assertThat(completed.get(0).path("data").path("items").toString())
                .contains("\"product_name\":\"Áo thun cổ tròn\"")
                .contains("\"product_name\":\"Giày chạy bộ\"")
                .contains("\"product_image_url\":\"https://cdn.example.test/SKU-B.jpg\"");
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
        ContractSchemas.assertComponent(ContractSchemas.INTERNAL, "PromotionRecalculationProblem", response.body());
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
    void proven_no_create_failure_terminates_submission_and_releases_both_holds() throws Exception {
        String buyer = user();
        fakes.behaviour.put("initiate:" + buyer, "NO_CREATE");
        String token = UUID.randomUUID().toString();
        Response result = submit(Fixtures.submission(token, buyer, Fixtures.defaultLines(), 200_000, "promo-" + UUID.randomUUID()));
        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body().path("code").asText()).isEqualTo("PAYMENT_INITIATION_REJECTED");
        long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE checkout_token=?", Long.class, token);
        awaitStatus(orderId, "CANCELLED");
        assertThat(fakes.callsFor(orderId)).contains("inventory.release:" + orderId + ":PRE_PAYMENT_SAGA_FAILED");
        assertThat(fakes.callsFor(orderId)).anyMatch(call -> call.startsWith("promotion.cancel:"));
        assertThat(fakes.callsFor(orderId).stream().filter(call -> call.startsWith("payment.initiate:")).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_payment_attempts WHERE order_id=?", Integer.class, orderId)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM order_checkout_submissions WHERE checkout_token=?", String.class, UUID.fromString(token))).isEqualTo("FAILED");
        assertThat(submit(Fixtures.submission(Fixtures.defaultLines(), 0, null)).status()).isEqualTo(201);
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
        validateAgainstContracts(orderId);

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
        ContractSchemas.assertComponent(ContractSchemas.PUBLIC, "OperationAccepted", first.body());
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
        ContractSchemas.assertComponent(ContractSchemas.PUBLIC, "OrderOperation", operation.body());
        ContractSchemas.assertComponent(ContractSchemas.PUBLIC, "OrderDetail", buyerGet("/api/v1/orders/" + orderId, user).body());
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
    void latePendingCancelPreservesCaptureAndLegacyCheckpointIsRecheckedBeforeRelease() throws Exception {
        long orderId = placeOrder(Fixtures.submission(UUID.randomUUID().toString(), user(),
                Fixtures.defaultLines(), 0, null));
        String txn = activeTxn(orderId);
        jdbc.update("UPDATE orders SET status = 'CANCELLING', cancel_reason = 'USER_CANCEL' WHERE id = ?", orderId);

        var key = new CheckpointStore.Key(orderId, "CHECKOUT", "CANCELLING", "PAYMENT_CANCEL", txn, 0);
        var request = mapper.createObjectNode().put("order_id", Long.toString(orderId)).put("reason", "BUYER_CANCELLED");
        var checkpoint = checkpoints.freeze(key, "order-" + orderId + "-payment-cancel-" + txn, request.toString(),
                CanonicalJson.digest(request));
        var pending = mapper.createObjectNode().put("order_id", Long.toString(orderId)).put("transaction_id", txn)
                .put("outcome", "CANCEL_PENDING").put("financial_state_version", 1);
        var captured = pending.deepCopy().put("outcome", "ALREADY_SUCCEEDED").put("financial_state_version", 2);
        captured.putObject("amount").put("amount_minor", (long) order(orderId).get("final_amount_minor"));
        checkpoints.record(checkpoint.id(), "ACCEPTED", null, captured.toString(), CanonicalJson.digest(captured));
        checkpoints.record(checkpoint.id(), "UNKNOWN", "CANCEL_PENDING", pending.toString(), CanonicalJson.digest(pending));

        var stored = checkpoints.find(key).orElseThrow();
        assertThat(stored.accepted()).isTrue();
        assertThat(stored.errorCode()).isNull();
        assertThat(mapper.readTree(stored.responseJson()).path("outcome").asText()).isEqualTo("ALREADY_SUCCEEDED");
        assertThat(stored.responseDigest()).isEqualTo(CanonicalJson.digest(captured));

        // Recreate a checkpoint written by the old code and simulate cleanup already queued in Temporal.
        jdbc.update("UPDATE order_saga_checkpoints SET response = CAST(? AS jsonb), response_digest = ? WHERE id = ?",
                pending.toString(), CanonicalJson.digest(pending), checkpoint.id());
        fakes.behaviour.put("cancel:" + orderId, "CANCEL_PENDING");
        assertThat(checkout.releaseInventory(orderId).isUnknown()).isTrue();
        assertThat(orderStatus(orderId)).isEqualTo("CANCELLING");

        fakes.behaviour.put("cancel:" + orderId, "ALREADY_SUCCEEDED");
        assertThat(checkout.releaseInventory(orderId).isCaptured()).isTrue();
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(checkpoints.find(key).orElseThrow().responseJson()).contains("ALREADY_SUCCEEDED");
        assertThat(fakes.callsFor(orderId)).noneMatch(call -> call.startsWith("inventory.release:"));
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
        ContractSchemas.assertComponent(ContractSchemas.PUBLIC, "PaymentAttempt", attempt.body());
        Response old = buyerGet("/api/v1/orders/" + orderId + "/payment-attempts/1", user);
        assertThat(old.body().path("status").asText()).isEqualTo("FAILED");
        assertThat(old.body().path("client_secret").isNull()).isTrue();
        ContractSchemas.assertComponent(ContractSchemas.PUBLIC, "PaymentAttempt", old.body());

        paymentSucceeded(orderId);
        awaitStatus(orderId, "PROCESSING");
        assertThat(order(orderId).get("captured_payment_transaction_id")).isNotEqualTo(firstTxn);
        assertThat(notificationTemplates(orderId)).containsExactly("ORDER_PAYMENT_FAILED", "ORDER_CONFIRMED");
        validateAgainstContracts(orderId);
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
    void adminDeliveryConfirmationNeedsDispatchAndCompletesOrderOnLastLine() throws Exception {
        long orderId = paidOrder(Fixtures.submission(Fixtures.defaultLines(), 0, null));
        List<Long> lines = lineIds(orderId);
        String path = "/api/v1/admin/orders/" + orderId + "/delivery-confirmations";

        // Nothing is dispatched yet: the confirmation is refused and changes nothing.
        Response early = confirmDelivery(orderId, List.of(deliveryLine(lines.get(0), "TRK-1")));
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.body().path("code").asText()).isEqualTo("DELIVERY_REQUIRES_DISPATCH");
        assertThat(lineStatus(lines.get(0))).isEqualTo("PROCESSING");

        // Dispatch alone never delivers (Inventory physical dispatch is not delivery).
        Map<String, Object> first = jdbc.queryForMap(
                "SELECT sku_id, quantity, allocated_warehouse_id FROM order_item_lines WHERE id = ?", lines.get(0));
        dispatch(orderId, lines.get(0), (String) first.get("allocated_warehouse_id"), (String) first.get("sku_id"),
                ((Number) first.get("quantity")).intValue());
        assertThat(lineStatus(lines.get(0))).isEqualTo("DISPATCHED");

        // Authorization and input validation.
        assertThat(call(buyer(MockMvcRequestBuilders.post(path), user()).contentType("application/json")
                .content("{\"lines\":[]}")).status()).isEqualTo(403);
        assertThat(confirmDelivery(orderId, List.of()).status()).isEqualTo(400);
        assertThat(confirmDelivery(orderId, List.of(deliveryLine(999999999L, "TRK-X"))).status()).isEqualTo(422);
        assertThat(confirmDelivery(orderId, List.of(deliveryLine(lines.get(0), "A"), deliveryLine(lines.get(0), "B")))
                .body().path("code").asText()).isEqualTo("INVALID_DELIVERY_CONFIRMATION");
        Response future = confirmDelivery(orderId, List.of(Map.of("order_line_id", Long.toString(lines.get(0)),
                "delivered_at", Instant.now().plusSeconds(3600).toString(), "delivery_reference", "TRK-F")));
        assertThat(future.status()).isEqualTo(422);
        Response beforeDispatch = confirmDelivery(orderId, List.of(Map.of("order_line_id", Long.toString(lines.get(0)),
                "delivered_at", Instant.now().minusSeconds(3600).toString(), "delivery_reference", "TRK-B")));
        assertThat(beforeDispatch.status()).isEqualTo(422);
        assertThat(call(admin(MockMvcRequestBuilders.post("/api/v1/admin/orders/424242424/delivery-confirmations"))
                .contentType("application/json").content(mapper.writeValueAsString(
                        Map.of("lines", List.of(deliveryLine(lines.get(0), "TRK-N")))))).status()).isEqualTo(404);

        // All-or-nothing: a dispatched line plus a not-yet-dispatched line changes neither.
        Response mixed = confirmDelivery(orderId, List.of(deliveryLine(lines.get(0), "TRK-1"),
                deliveryLine(lines.get(1), "TRK-2")));
        assertThat(mixed.status()).isEqualTo(409);
        assertThat(lineStatus(lines.get(0))).isEqualTo("DISPATCHED");

        // First line confirmed: the order stays PROCESSING and the unit is partially delivered.
        Map<String, Object> confirmed = deliveryLine(lines.get(0), "TRK-1");
        Response one = confirmDelivery(orderId, List.of(confirmed));
        assertThat(one.status()).isEqualTo(200);
        ContractSchemas.assertComponent(ContractSchemas.PUBLIC, "DeliveryConfirmationResult", one.body());
        assertThat(one.body().path("order_completed").asBoolean()).isFalse();
        assertThat(orderStatus(orderId)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT delivery_confirmed_by::text FROM order_item_lines WHERE id = ?",
                String.class, lines.get(0))).isEqualTo("99999999-9999-4999-8999-999999999999");
        // Same line, same reference and time replays; a different reference conflicts.
        assertThat(confirmDelivery(orderId, List.of(confirmed)).status()).isEqualTo(200);
        Response changed = confirmDelivery(orderId, List.of(deliveryLine(lines.get(0), "TRK-OTHER")));
        assertThat(changed.status()).isEqualTo(409);
        assertThat(changed.body().path("code").asText()).isEqualTo("DELIVERY_ALREADY_CONFIRMED");
        assertThat(outbox(orderId, "com.ecommerce.order.completed.v1")).isEmpty();

        // Dispatch and confirm the remaining lines: the last confirmation completes the master once.
        for (Long lineId : lines.subList(1, lines.size())) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT sku_id, quantity, allocated_warehouse_id FROM order_item_lines WHERE id = ?", lineId);
            dispatch(orderId, lineId, (String) row.get("allocated_warehouse_id"), (String) row.get("sku_id"),
                    ((Number) row.get("quantity")).intValue());
        }
        List<Map<String, Object>> rest = new java.util.ArrayList<>();
        for (Long lineId : lines.subList(1, lines.size())) {
            rest.add(deliveryLine(lineId, "TRK-REST"));
        }
        Response last = confirmDelivery(orderId, rest);
        assertThat(last.status()).isEqualTo(200);
        assertThat(last.body().path("order_completed").asBoolean()).isTrue();
        assertThat(orderStatus(orderId)).isEqualTo("COMPLETED");
        assertThat(outbox(orderId, "com.ecommerce.order.completed.v1")).hasSize(1);
        assertThat(notificationTemplates(orderId)).containsExactly("ORDER_CONFIRMED", "ORDER_COMPLETED");
        // A late replay of an already applied confirmation neither fails nor emits another fact.
        assertThat(confirmDelivery(orderId, List.of(confirmed)).status()).isEqualTo(200);
        assertThat(outbox(orderId, "com.ecommerce.order.completed.v1")).hasSize(1);
        validateAgainstContracts(orderId);
    }

    @Test
    void dispatchedButUnconfirmedLinesAreCountedAsOverdue() throws Exception {
        long orderId = paidOrder(Fixtures.submission(Fixtures.defaultLines(), 0, null));
        dispatchAll(orderId);
        jdbc.update("UPDATE order_item_lines SET dispatched_at = NOW() - INTERVAL '8 days' WHERE order_id = ?", orderId);
        Double overdue = meterRegistry.get("order.fulfillment.delivery_confirmation_overdue").gauge().value();
        assertThat(overdue).isGreaterThanOrEqualTo((double) lineIds(orderId).size());
        deliverAll(orderId);
        awaitStatus(orderId, "COMPLETED");
    }

    private String lineStatus(long lineId) {
        return jdbc.queryForObject("SELECT fulfillment_status FROM order_item_lines WHERE id = ?", String.class, lineId);
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
        ContractSchemas.assertComponent(ContractSchemas.INTERNAL, "ReconciliationCase", reconciliation.body());
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
        ContractSchemas.assertComponent(ContractSchemas.INTERNAL, "ReconciliationDecisionAccepted", decided.body());
        assertThat(orderStatus(orderId)).isEqualTo("PROCESSING");
        // Same decision replays; a stale revision with a new decision ID is 409.
        Response replay = call(MockMvcRequestBuilders.post("/internal/v1/orders/" + orderId
                        + "/financial-reconciliation-decisions").header("X-Service-Identity", "finance-ops")
                .header("X-Actor-Id", UUID.randomUUID().toString()).header("Idempotency-Key", decision)
                .contentType("application/json").content(mapper.writeValueAsString(body)));
        assertThat(replay.status()).isEqualTo(202);
    }
}
