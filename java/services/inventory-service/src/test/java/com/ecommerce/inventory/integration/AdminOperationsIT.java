package com.ecommerce.inventory.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ADMIN surface: warehouse master, sellable stock, reversals and operational queues. */
class AdminOperationsIT extends AbstractIntegrationTest {

    private static final String ADMIN = "/api/v1/admin/inventory";

    @Test
    void warehouseUpdateNeedsTheCurrentRevision() throws Exception {
        String wh = createWarehouse("Main", 0);
        Map<String, Object> patch = new HashMap<>(Map.of("expected_revision", 1, "name", "Renamed",
                "reason", "rename", "evidence_reference", "evidence://rename"));
        Response first = admin(HttpMethod.PATCH, ADMIN + "/warehouses/" + wh, patch, key());
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().path("warehouse").path("name").asText()).isEqualTo("Renamed");
        assertThat(first.body().path("warehouse").path("revision").asLong()).isEqualTo(2);
        assertThat(first.body().path("warehouse").path("is_active").asBoolean()).isTrue();

        Response stale = admin(HttpMethod.PATCH, ADMIN + "/warehouses/" + wh, patch, key());
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().path("code").asText()).isEqualTo("WAREHOUSE_CONFLICT");

        Response missing = admin(HttpMethod.PATCH, ADMIN + "/warehouses/unknown", patch, key());
        assertThat(missing.status()).isEqualTo(404);

        Response nothing = admin(HttpMethod.PATCH, ADMIN + "/warehouses/" + wh, Map.of("expected_revision", 2,
                "reason", "noop", "evidence_reference", "evidence://noop"), key());
        assertThat(nothing.status()).isEqualTo(400);

        JsonNode page = admin(HttpMethod.GET, ADMIN + "/warehouses?is_active=true&limit=1", null, null).body();
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.has("next_cursor")).isTrue();
        assertThat(page.path("next_cursor").isNull()).isTrue();
    }

    @Test
    void adminRoleIsRequired() throws Exception {
        int status = mvc.perform(MockMvcRequestBuilders.get(ADMIN + "/warehouses")
                .header("X-User-Id", "u-1").header("X-User-Roles", "CUSTOMER")).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(403);
        int anonymous = mvc.perform(MockMvcRequestBuilders.get(ADMIN + "/warehouses")).andReturn().getResponse()
                .getStatus();
        assertThat(anonymous).isEqualTo(401);
    }

    @Test
    void receiptAdjustmentAndReversalRules() throws Exception {
        String wh = createWarehouse("Main", 0);
        Response unknown = receive("missing-warehouse", "sku-a", 1);
        assertThat(unknown.status()).isEqualTo(409);
        assertThat(unknown.body().path("code").asText()).isEqualTo("WAREHOUSE_NOT_FOUND");

        Response receipt = receive(wh, "sku-a", 10);
        String movementId = receipt.body().path("movement_id").asText();
        assertThat(receipt.body().path("operation_id").asText()).isNotBlank();

        reserve("order-1", "l1", wh, "sku-a", "8");
        Response tooMuch = adjust(wh, "sku-a", "DECREASE", 3, "COUNT_LOSS");
        assertThat(tooMuch.body().path("code").asText()).isEqualTo("INSUFFICIENT_SELLABLE_STOCK");
        Response wrongDirection = adjust(wh, "sku-a", "INCREASE", 3, "COUNT_LOSS");
        assertThat(wrongDirection.status()).isEqualTo(400);
        assertThat(adjust(wh, "sku-a", "DECREASE", 2, "COUNT_LOSS").status()).isEqualTo(200);

        Response gap = reverse(movementId, 2, 1);
        assertThat(gap.body().path("code").asText()).isEqualTo("MOVEMENT_NOT_REVERSIBLE");
        Response noSellable = reverse(movementId, 1, 1);
        assertThat(noSellable.body().path("code").asText()).isEqualTo("INSUFFICIENT_SELLABLE_STOCK");
        workflow(HttpMethod.POST, "/internal/inventory/v1/reservations/order-1/release",
                Map.of("reason", "PAYMENT_FAILED"), key());
        Response first = reverse(movementId, 1, 6);
        assertThat(first.status()).isEqualTo(200);
        Response over = reverse(movementId, 2, 5);
        assertThat(over.body().path("code").asText()).isEqualTo("MOVEMENT_NOT_REVERSIBLE");
        Response reversalOfReversal = reverse(first.body().path("movement_id").asText(), 1, 1);
        assertThat(reversalOfReversal.body().path("code").asText()).isEqualTo("MOVEMENT_NOT_REVERSIBLE");
        Response missingMovement = reverse("no-such-movement", 1, 1);
        assertThat(missingMovement.status()).isEqualTo(404);

        JsonNode pos = position(wh, "sku-a");
        assertThat(pos.path("on_hand").asLong()).isEqualTo(2);
        assertThat(pos.path("available_to_sell").asLong()).isEqualTo(2);
        JsonNode movements = admin(HttpMethod.GET, ADMIN + "/stock-movements?warehouse_id=" + wh + "&limit=2", null,
                null).body();
        assertThat(movements.path("items")).hasSize(2);
        assertThat(movements.path("next_cursor").asText()).isNotBlank();
        JsonNode next = admin(HttpMethod.GET, ADMIN + "/stock-movements?warehouse_id=" + wh + "&limit=100&cursor="
                + movements.path("next_cursor").asText(), null, null).body();
        assertThat(next.path("items").size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void operationIdentityIsClaimedOnlyBySuccess() throws Exception {
        String wh = createWarehouse("Main", 0);
        Map<String, Object> adjustment = adjustment(wh, "sku-a", "DECREASE", 1, "COUNT_LOSS");
        Response rejected = admin(HttpMethod.POST, ADMIN + "/stock-adjustments", adjustment, key());
        assertThat(rejected.body().path("code").asText()).isEqualTo("INSUFFICIENT_SELLABLE_STOCK");
        receive(wh, "sku-a", 1);
        Response accepted = admin(HttpMethod.POST, ADMIN + "/stock-adjustments", adjustment, key());
        assertThat(accepted.status()).isEqualTo(200);
        Response duplicate = admin(HttpMethod.POST, ADMIN + "/stock-adjustments", adjustment, key());
        assertThat(duplicate.body().path("code").asText()).isEqualTo("OPERATION_IDENTITY_CONFLICT");
    }

    @Test
    void dispatchRecordsTheWholeLineAndWritesTheOrderFact() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 5);
        reserve("order-1", "l1", wh, "sku-a", "3");
        Response notCommitted = dispatch("order-1", "l1", wh, "sku-a", 3);
        assertThat(notCommitted.body().path("code").asText()).isEqualTo("DISPATCH_NOT_ALLOWED");
        workflow(HttpMethod.POST, "/internal/inventory/v1/reservations/order-1/commit",
                Map.of("payment_reference", "p"), key());

        JsonNode queue = admin(HttpMethod.GET, ADMIN + "/dispatch-queue?warehouse_id=" + wh, null, null).body();
        assertThat(queue.path("items")).hasSize(1);
        assertThat(queue.path("items").get(0).path("order_line_id").asText()).isEqualTo("l1");

        assertThat(dispatch("order-1", "l1", wh, "sku-a", 2).body().path("code").asText())
                .isEqualTo("DISPATCH_NOT_ALLOWED");
        Response ok = dispatch("order-1", "l1", wh, "sku-a", 3);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(position(wh, "sku-a").path("on_hand").asLong()).isEqualTo(2);
        JsonNode event = objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE event_type LIKE '%line-dispatched%'", String.class));
        assertThat(event.path("aggregaterevision").asLong()).isEqualTo(3);
        assertThat(event.path("data").path("reservation_revision").asLong()).isEqualTo(3);
        assertThat(event.path("aggregateid").asText()).isEqualTo("order-1:l1");
        assertThat(admin(HttpMethod.GET, ADMIN + "/reservations/order-1", null, null).body().path("state").asText())
                .isEqualTo("DISPATCHED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sku_availability_dirty WHERE sku_id = 'sku-a' "
                + "AND mark_seq > 2", Integer.class)).isZero();
    }

    private Response adjust(String wh, String sku, String direction, long quantity, String reason) throws Exception {
        return admin(HttpMethod.POST, ADMIN + "/stock-adjustments", adjustment(wh, sku, direction, quantity, reason),
                key());
    }

    private Map<String, Object> adjustment(String wh, String sku, String direction, long quantity, String reason) {
        return Map.of("warehouse_id", wh, "sku_id", sku, "direction", direction, "quantity", quantity,
                "adjustment_case_id", "case-" + direction + quantity, "adjustment_line_id", "1", "reason", reason,
                "evidence_reference", "evidence://count");
    }

    private Response reverse(String movementId, int sequence, long quantity) throws Exception {
        return admin(HttpMethod.POST, ADMIN + "/stock-movements/" + movementId + "/reversals", Map.of(
                "reversal_sequence", sequence, "quantity", quantity, "reason", "WRONG_QUANTITY",
                "reason_note", "typo", "correction_case_reference", "case-1",
                "evidence_reference", "evidence://typo"), key());
    }
}
