package com.ecommerce.inventory.integration;

import com.ecommerce.inventory.command.WriteGuard;
import com.ecommerce.inventory.worker.AvailabilityProjector;
import com.ecommerce.inventory.worker.ExportSequencer;
import com.ecommerce.inventory.worker.ReadinessPublisher;
import com.ecommerce.inventory.worker.WarehouseFanoutWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-SKU availability projection, BFF read freshness, Search readiness, commit-ordered
 * export and the emitted CloudEvents against the published JSON Schemas.
 */
class AvailabilityExportIT extends AbstractIntegrationTest {

    private static final String READ = "/internal/inventory/v1/sku-availabilities:batch-get";
    private static final String EXPORT = "/exports/inventory/v1";

    @Autowired
    AvailabilityProjector projector;
    @Autowired
    WarehouseFanoutWorker fanout;
    @Autowired
    ReadinessPublisher readiness;
    @Autowired
    ExportSequencer sequencer;
    @Autowired
    WriteGuard writeGuard;

    private JsonNode read(String... skus) throws Exception {
        return service("product-detail-bff", HttpMethod.POST, READ, Map.of("sku_ids", List.of(skus)), null).body();
    }

    private static JsonNode item(JsonNode response, String sku) {
        for (JsonNode item : response.path("items")) {
            if (item.path("sku_id").asText().equals(sku)) {
                return item;
            }
        }
        throw new AssertionError("missing " + sku);
    }

    private void drain() {
        while (fanout.runOnce()) {
            // checkpointed batches
        }
        projector.runOnce();
        readiness.runOnce();
        sequencer.runOnce();
    }

    @Test
    void readReturnsLastStateWithinBudgetAndUnknownOtherwise() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 1);
        JsonNode before = read("sku-a", "sku-never");
        assertThat(item(before, "sku-a").path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(item(before, "sku-a").path("availability_revision").isNull()).isTrue();
        assertThat(item(before, "sku-never").path("status").asText()).isEqualTo("UNKNOWN");

        projector.runOnce();
        JsonNode known = item(read("sku-a"), "sku-a");
        assertThat(known.path("status").asText()).isEqualTo("IN_STOCK");
        assertThat(known.path("availability_revision").asLong()).isEqualTo(1);
        assertThat(known.has("quantity")).isFalse();

        reserve("order-1", "l1", wh, "sku-a", "1");
        assertThat(item(read("sku-a"), "sku-a").path("status").asText()).isEqualTo("IN_STOCK");
        jdbc.update("UPDATE sku_availability_dirty SET first_marked_at = now() - interval '31 seconds'");
        assertThat(item(read("sku-a"), "sku-a").path("status").asText()).isEqualTo("UNKNOWN");
        projector.runOnce();
        JsonNode out = item(read("sku-a"), "sku-a");
        assertThat(out.path("status").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(out.path("availability_revision").asLong()).isEqualTo(2);

        Response duplicate = service("product-detail-bff", HttpMethod.POST, READ,
                Map.of("sku_ids", List.of("a", "a")), null);
        assertThat(duplicate.status()).isEqualTo(400);
        Response foreign = service("search-service", HttpMethod.POST, READ, Map.of("sku_ids", List.of("a")), null);
        assertThat(foreign.status()).isEqualTo(403);
    }

    @Test
    void warehouseOutageFansOutAndCountsAsPendingWork() throws Exception {
        String a = createWarehouse("A", 0);
        String b = createWarehouse("B", 1);
        receive(a, "sku-1", 1);
        receive(a, "sku-2", 1);
        receive(b, "sku-2", 1);
        projector.runOnce();
        JsonNode warehouse = admin(HttpMethod.GET, "/api/v1/admin/inventory/warehouses/" + a, null, null).body();
        admin(HttpMethod.PATCH, "/api/v1/admin/inventory/warehouses/" + a, Map.of(
                "expected_revision", warehouse.path("revision").asLong(), "is_active", false,
                "reason", "closing", "evidence_reference", "e://close"), key());
        jdbc.update("UPDATE availability_fanout_job SET created_at = now() - interval '40 seconds'");
        assertThat(item(read("sku-1"), "sku-1").path("status").asText()).isEqualTo("UNKNOWN");

        drain();
        JsonNode result = read("sku-1", "sku-2");
        assertThat(item(result, "sku-1").path("status").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(item(result, "sku-2").path("status").asText()).isEqualTo("IN_STOCK");
    }

    @Test
    void nothingIsPublishedBeforeReadinessAndRegistrationPublishesTheCurrentSnapshot() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 2);
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM published_event", Integer.class)).isZero();

        Response registered = service("search-service", HttpMethod.PUT, EXPORT + "/search-projection-readiness/sku-a",
                Map.of("catalog_revision", 3), null);
        assertThat(registered.status()).isEqualTo(200);
        assertThat(registered.body().path("catalog_revision").asLong()).isEqualTo(3);
        service("search-service", HttpMethod.PUT, EXPORT + "/search-projection-readiness/sku-new",
                Map.of("catalog_revision", 1), null);
        Response stale = service("search-service", HttpMethod.PUT, EXPORT + "/search-projection-readiness/sku-a",
                Map.of("catalog_revision", 2), null);
        assertThat(stale.body().path("catalog_revision").asLong()).isEqualTo(3);
        drain();

        JsonNode changes = service("search-service", HttpMethod.GET, EXPORT + "/changes?after_sequence=0", null, null)
                .body();
        assertThat(changes.path("items")).hasSize(2);
        assertThat(changes.path("next_after_sequence").isNull()).isTrue();
        assertThat(changes.path("latest_sequence").asLong()).isEqualTo(2);
        JsonNode newSku = changes.path("items").get(1);
        assertThat(newSku.path("data").path("sku_id").asText()).isEqualTo("sku-new");
        assertThat(newSku.path("data").path("is_in_stock").asBoolean()).isFalse();

        JsonNode session = service("search-service", HttpMethod.POST, EXPORT + "/snapshot-sessions", null, null).body();
        assertThat(session.path("export_watermark").asLong()).isEqualTo(2);
        JsonNode page = service("search-service", HttpMethod.GET, EXPORT + "/snapshot-sessions/"
                + session.path("snapshot_id").asText() + "/sku-availabilities?limit=1", null, null).body();
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("is_in_stock").asBoolean()).isTrue();
        String cursor = page.path("next_cursor").asText();
        JsonNode last = service("search-service", HttpMethod.GET, EXPORT + "/snapshot-sessions/"
                + session.path("snapshot_id").asText() + "/sku-availabilities?cursor=" + cursor, null, null).body();
        assertThat(last.path("items")).hasSize(1);
        assertThat(last.path("next_cursor").isNull()).isTrue();

        Response staleRevoke = service("search-service", HttpMethod.DELETE,
                EXPORT + "/search-projection-readiness/sku-a?catalog_revision=2", null, null);
        assertThat(staleRevoke.status()).isEqualTo(409);
        assertThat(service("search-service", HttpMethod.DELETE,
                EXPORT + "/search-projection-readiness/sku-a?catalog_revision=4", null, null).status()).isEqualTo(204);
        reserve("order-1", "l1", wh, "sku-a", "2");
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM published_event", Integer.class)).isEqualTo(2);
    }

    @Test
    void staleSequencerCannotAssignAfterAnotherInstanceTookTheLease() throws Exception {
        String wh = createWarehouse("Main", 0);
        service("search-service", HttpMethod.PUT, EXPORT + "/search-projection-readiness/sku-a",
                Map.of("catalog_revision", 1), null);
        receive(wh, "sku-a", 1);
        projector.runOnce();
        readiness.runOnce();
        jdbc.update("UPDATE worker_lease SET holder = 'other-instance', fencing_token = fencing_token + 1, "
                + "expires_at = now() + interval '1 minute'");
        assertThat(sequencer.runOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM published_event", Integer.class)).isZero();
        jdbc.update("UPDATE worker_lease SET expires_at = now() - interval '1 second'");
        assertThat(sequencer.runOnce()).isPositive();
    }

    @Test
    void emittedCloudEventsMatchTheirContractSchemas() throws Exception {
        String wh = createWarehouse("Main", 0);
        service("search-service", HttpMethod.PUT, EXPORT + "/search-projection-readiness/sku-a",
                Map.of("catalog_revision", 1), null);
        receive(wh, "sku-a", 5);
        reserve("order-1", "l1", wh, "sku-a", "2");
        workflow(HttpMethod.POST, "/internal/inventory/v1/reservations/order-1/commit",
                Map.of("payment_reference", "p"), key());
        dispatch("order-1", "l1", wh, "sku-a", 2);
        String scope = workflow(HttpMethod.POST, "/internal/inventory/v1/refund-scopes", Map.of("order_id", "order-1",
                "refund_request_id", "rr-1", "lines", List.of(Map.of("order_line_id", "l1", "sku_id", "sku-a",
                        "expected_quantity", 2))), key()).body().path("refund_scope_id").asText();
        admin(HttpMethod.POST, "/api/v1/admin/inventory/return-receipts", Map.of("refund_scope_id", scope,
                "order_line_id", "l1", "actual_sku_id", "sku-a", "warehouse_id", wh, "quantity", 2,
                "return_receipt_id", "ret-1", "return_receipt_line_id", "1", "package_reference", "pkg",
                "received_at", "2026-10-05T09:00:00Z", "evidence_reference", "e://r"), key());
        admin(HttpMethod.POST, "/api/v1/admin/inventory/inspections", Map.of("inspection_id", "insp-1",
                "return_receipt_id", "ret-1", "return_receipt_line_id", "1", "warehouse_id", wh, "quantity", 2,
                "result", "PASS", "checklist_reference", "c", "evidence_reference", "e://i"), key());
        drain();

        Path contracts = Paths.get("").toAbsolutePath().resolve("../../../contracts").normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(contracts),
                "contracts/ is present only inside the repository");
        String prefix = "https://contracts.ecommerce.local/";
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, builder ->
                builder.schemaMappers(m -> m.mapPrefix(prefix, contracts.toUri().toString())));
        Map<String, String> schemaByType = Map.of(
                "com.ecommerce.inventory.sku-availability.changed.v1", "sku-availability-changed.schema.json",
                "com.ecommerce.inventory.reservation.line-dispatched.v1", "reservation-line-dispatched.schema.json",
                "com.ecommerce.inventory.refund.all-items-inspection-completed.v1",
                "all-items-inspection-completed.schema.json");

        List<String> payloads = jdbc.queryForList("SELECT payload::text FROM published_event ORDER BY sequence",
                String.class);
        assertThat(payloads).hasSizeGreaterThanOrEqualTo(3);
        for (String payload : payloads) {
            JsonNode event = objectMapper.readTree(payload);
            JsonSchema schema = factory.getSchema(SchemaLocation.of(prefix + "events/inventory/v1/schemas/"
                    + schemaByType.get(event.path("type").asText())));
            Set<ValidationMessage> errors = schema.validate(event);
            assertThat(errors).as(event.path("type").asText()).isEmpty();
        }
    }

    @Test
    void writeFreezeFailsMutationsClosedButKeepsReads() throws Exception {
        String wh = createWarehouse("Main", 0);
        writeGuard.updateUnsequencedOutbox(Long.MAX_VALUE);
        try {
            Response refused = receive(wh, "sku-a", 1);
            assertThat(refused.status()).isEqualTo(503);
            assertThat(refused.body().path("code").asText()).isEqualTo("INVENTORY_UNAVAILABLE");
            assertThat(refused.body().path("retryable").asBoolean()).isTrue();
            assertThat(admin(HttpMethod.GET, "/api/v1/admin/inventory/warehouses/" + wh, null, null).status())
                    .isEqualTo(200);
        } finally {
            writeGuard.updateUnsequencedOutbox(0);
        }
        assertThat(receive(wh, "sku-a", 1).status()).isEqualTo(200);
    }
}
