package com.ecommerce.inventory.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Workflow API: preview, exact-plan Reserve, Commit/Release/recovery and command replay. */
class ReservationWorkflowIT extends AbstractIntegrationTest {

    private static final String BASE = "/internal/inventory/v1";

    @Test
    void previewPicksPriorityAndRejectsWholeOrderWhenOneLineCannotAllocate() throws Exception {
        String primary = createWarehouse("Primary", 0);
        String secondary = createWarehouse("Secondary", 5);
        receive(primary, "sku-a", 2);
        receive(secondary, "sku-a", 10);

        Response ok = service("checkout-service", HttpMethod.POST, BASE + "/allocation-previews",
                Map.of("lines", List.of(Map.of("line_reference", "sku-a", "sku_id", "sku-a", "quantity", 5))), null);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().path("outcome").asText()).isEqualTo("ALLOCATABLE");
        assertThat(ok.body().path("lines").get(0).path("warehouse_id").asText()).isEqualTo(secondary);
        assertThat(ok.body().path("allocation_plan_id").asText()).isNotBlank();

        Response rejected = service("checkout-service", HttpMethod.POST, BASE + "/allocation-previews",
                Map.of("lines", List.of(Map.of("line_reference", "sku-a", "sku_id", "sku-a", "quantity", 5),
                        Map.of("line_reference", "sku-x", "sku_id", "sku-x", "quantity", 1))), null);
        assertThat(rejected.body().path("outcome").asText()).isEqualTo("REJECT_ALL");
        assertThat(rejected.body().path("rejected_lines")).hasSize(1);
        assertThat(rejected.body().path("rejected_lines").get(0).path("reason").asText())
                .isEqualTo("NO_ELIGIBLE_WAREHOUSE");
    }

    @Test
    void reserveIsAllOrNothingAndReplaysTheSameReceipt() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 5);
        receive(wh, "sku-b", 1);

        Response rejectAll = reserve("order-1", "l1", wh, "sku-a", "2", "l2", wh, "sku-b", "2");
        assertThat(rejectAll.status()).isEqualTo(200);
        assertThat(rejectAll.body().path("outcome").asText()).isEqualTo("REJECT_ALL");
        assertThat(rejectAll.body().path("rejected_lines").get(0).asText()).isEqualTo("l2");
        assertThat(position(wh, "sku-a").path("available_to_sell").asLong()).isEqualTo(5);

        String key = key();
        Map<String, Object> body = Map.of("order_id", "order-2", "allocation_plan_id", "unknown-plan",
                "lines", List.of(Map.of("order_line_id", "l1", "warehouse_id", wh, "sku_id", "sku-a", "quantity", 2)));
        Response reserved = workflow(HttpMethod.POST, BASE + "/reservations", body, key);
        assertThat(reserved.status()).isEqualTo(200);
        assertThat(reserved.body().path("outcome").asText()).isEqualTo("RESERVED");
        assertThat(reserved.body().path("replayed").asBoolean()).isFalse();
        assertThat(reserved.body().path("reservation_revision").asLong()).isEqualTo(1);

        Response replay = workflow(HttpMethod.POST, BASE + "/reservations", body, key);
        assertThat(replay.body().path("outcome").asText()).isEqualTo("RESERVED");
        assertThat(replay.body().path("replayed").asBoolean()).isTrue();
        assertThat(replay.body().path("recorded_at")).isEqualTo(reserved.body().path("recorded_at"));

        Response reused = workflow(HttpMethod.POST, BASE + "/reservations",
                Map.of("order_id", "order-3", "allocation_plan_id", "p", "lines", body.get("lines")), key);
        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.body().path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        Response duplicate = workflow(HttpMethod.POST, BASE + "/reservations", body, key());
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.body().path("code").asText()).isEqualTo("RESERVATION_ALREADY_EXISTS");

        JsonNode pos = position(wh, "sku-a");
        assertThat(pos.path("available_to_sell").asLong()).isEqualTo(3);
        assertThat(pos.path("reserved").asLong()).isEqualTo(2);
        assertThat(pos.path("on_hand").asLong()).isEqualTo(5);

        Response command = workflow(HttpMethod.GET, BASE + "/commands/" + key, null, null);
        assertThat(command.body().path("result").asText()).isEqualTo("ACCEPTED");
        assertThat(command.body().path("outcome").asText()).isEqualTo("RESERVED");
        assertThat(command.body().path("operation").asText()).isEqualTo("RESERVE_ORDER");
    }

    @Test
    void staleWarehouseRejectsWithoutTryingAnotherWarehouse() throws Exception {
        String outage = createWarehouse("Outage", 0);
        String backup = createWarehouse("Backup", 1);
        receive(outage, "sku-a", 5);
        receive(backup, "sku-a", 5);
        JsonNode warehouse = admin(HttpMethod.GET, "/api/v1/admin/inventory/warehouses/" + outage, null, null).body();
        admin(HttpMethod.PATCH, "/api/v1/admin/inventory/warehouses/" + outage, Map.of(
                "expected_revision", warehouse.path("revision").asLong(), "operational_state", "OUTAGE",
                "reason", "flood", "evidence_reference", "evidence://flood"), key());

        Response response = reserve("order-1", "l1", outage, "sku-a", "1");
        assertThat(response.body().path("outcome").asText()).isEqualTo("REJECT_ALL");
        assertThat(position(backup, "sku-a").path("reserved").asLong()).isZero();
    }

    @Test
    void commitIsNotDeadlineGatedAndRejectsANonReservedRootDurably() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 5);
        reserve("order-1", "l1", wh, "sku-a", "2");
        jdbc.update("UPDATE order_reservation SET expires_at = now() - interval '1 hour' WHERE order_id = 'order-1'");

        Response commit = workflow(HttpMethod.POST, BASE + "/reservations/order-1/commit",
                Map.of("payment_reference", "pay-1"), key());
        assertThat(commit.status()).isEqualTo(200);
        assertThat(commit.body().path("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(commit.body().path("reservation_revision").asLong()).isEqualTo(2);
        assertThat(position(wh, "sku-a").path("committed").asLong()).isEqualTo(2);

        String key = key();
        Response again = workflow(HttpMethod.POST, BASE + "/reservations/order-1/commit",
                Map.of("payment_reference", "pay-1"), key);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().path("code").asText()).isEqualTo("RESERVATION_NOT_RESERVED");
        Response replayed = workflow(HttpMethod.POST, BASE + "/reservations/order-1/commit",
                Map.of("payment_reference", "pay-1"), key);
        assertThat(replayed.status()).isEqualTo(409);
        assertThat(replayed.body()).isEqualTo(again.body());
        JsonNode outcome = workflow(HttpMethod.GET, BASE + "/commands/" + key, null, null).body();
        assertThat(outcome.path("result").asText()).isEqualTo("REJECTED");
        assertThat(outcome.path("http_status").asInt()).isEqualTo(409);
        assertThat(outcome.path("error_code").asText()).isEqualTo("RESERVATION_NOT_RESERVED");

        Response unknown = workflow(HttpMethod.POST, BASE + "/reservations/nope/commit",
                Map.of("payment_reference", "pay-1"), key());
        assertThat(unknown.body().path("code").asText()).isEqualTo("RESERVATION_NOT_RESERVED");
    }

    @Test
    void onlyAnExpiredReleaseCanBeRecoveredAsAWholeRoot() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 3);
        reserve("order-exp", "l1", wh, "sku-a", "2");
        reserve("order-buyer", "l1", wh, "sku-a", "1");
        workflow(HttpMethod.POST, BASE + "/reservations/order-exp/release",
                Map.of("reason", "RESERVATION_EXPIRED"), key());
        workflow(HttpMethod.POST, BASE + "/reservations/order-buyer/release",
                Map.of("reason", "BUYER_CANCELLED"), key());

        Response notRecoverable = workflow(HttpMethod.POST, BASE + "/reservations/order-buyer/recover-timed-out-payment",
                Map.of("payment_reference", "pay-late"), key());
        assertThat(notRecoverable.body().path("code").asText()).isEqualTo("RESERVATION_NOT_RECOVERABLE");

        // Another order takes the stock first: recovery must not oversell.
        reserve("order-other", "l1", wh, "sku-a", "2");
        Response short1 = workflow(HttpMethod.POST, BASE + "/reservations/order-exp/recover-timed-out-payment",
                Map.of("payment_reference", "pay-late"), key());
        assertThat(short1.body().path("code").asText()).isEqualTo("RESERVATION_NOT_RECOVERABLE");
        workflow(HttpMethod.POST, BASE + "/reservations/order-other/release", Map.of("reason", "PAYMENT_FAILED"), key());

        Response recovered = workflow(HttpMethod.POST, BASE + "/reservations/order-exp/recover-timed-out-payment",
                Map.of("payment_reference", "pay-late"), key());
        assertThat(recovered.body().path("outcome").asText()).isEqualTo("COMMITTED");
        JsonNode reservation = workflow(HttpMethod.GET, BASE + "/reservations/order-exp", null, null).body();
        assertThat(reservation.path("state").asText()).isEqualTo("COMMITTED");
        assertThat(reservation.path("release_reason").asText()).isEqualTo("RESERVATION_EXPIRED");
        assertThat(position(wh, "sku-a").path("committed").asLong()).isEqualTo(2);
        assertThat(position(wh, "sku-a").path("available_to_sell").asLong()).isEqualTo(1);
    }

    @Test
    void preDispatchReleaseIsAllOrNothingAgainstDispatch() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 5);
        reserve("order-1", "l1", wh, "sku-a", "1", "l2", wh, "sku-a", "2");
        workflow(HttpMethod.POST, BASE + "/reservations/order-1/commit", Map.of("payment_reference", "p"), key());
        assertThat(dispatch("order-1", "l1", wh, "sku-a", 1).status()).isEqualTo(200);

        Response refused = workflow(HttpMethod.POST, BASE + "/reservations/order-1/release-committed-before-dispatch",
                Map.of("reason", "ADMIN_FORCE_REFUND_BEFORE_DISPATCH"), key());
        assertThat(refused.body().path("code").asText()).isEqualTo("PRE_DISPATCH_RELEASE_NOT_ALLOWED");
        assertThat(workflow(HttpMethod.GET, BASE + "/reservations/order-1", null, null).body().path("state").asText())
                .isEqualTo("PARTIALLY_DISPATCHED");

        reserve("order-2", "l1", wh, "sku-a", "2");
        workflow(HttpMethod.POST, BASE + "/reservations/order-2/commit", Map.of("payment_reference", "p"), key());
        Response released = workflow(HttpMethod.POST, BASE + "/reservations/order-2/release-committed-before-dispatch",
                Map.of("reason", "ADMIN_FORCE_REFUND_BEFORE_DISPATCH"), key());
        assertThat(released.body().path("outcome").asText()).isEqualTo("RELEASED");
        assertThat(released.body().path("released_order_line_ids").get(0).asText()).isEqualTo("l1");
        JsonNode reservation = workflow(HttpMethod.GET, BASE + "/reservations/order-2", null, null).body();
        assertThat(reservation.has("release_reason")).isFalse();
        JsonNode pos = position(wh, "sku-a");
        assertThat(pos.path("on_hand").asLong()).isEqualTo(4);
        assertThat(pos.path("committed").asLong()).isEqualTo(2);
        assertThat(pos.path("available_to_sell").asLong()).isEqualTo(2);
    }

    @Test
    void missingIdentityAndForeignServiceAreRefused() throws Exception {
        Response missing = service(null, HttpMethod.GET, BASE + "/reservations/x", null, null);
        assertThat(missing.status()).isEqualTo(401);
        assertThat(missing.body().path("code").asText()).isEqualTo("UNAUTHENTICATED");
        Response foreign = service("checkout-service", HttpMethod.GET, BASE + "/reservations/x", null, null);
        assertThat(foreign.status()).isEqualTo(403);
        Response missingKey = workflow(HttpMethod.POST, BASE + "/reservations/x/commit",
                Map.of("payment_reference", "p"), null);
        assertThat(missingKey.status()).isEqualTo(400);
        assertThat(missingKey.body().path("code").asText()).isEqualTo("INVALID_REQUEST");
        Response badReason = workflow(HttpMethod.POST, BASE + "/refund-scopes/x/close",
                Map.of("reason", "CANCELLED"), key());
        assertThat(badReason.status()).isEqualTo(400);
    }
}
