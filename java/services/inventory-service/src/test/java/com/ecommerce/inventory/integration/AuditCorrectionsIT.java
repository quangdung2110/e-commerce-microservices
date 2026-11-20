package com.ecommerce.inventory.integration;

import com.ecommerce.inventory.worker.AvailabilityProjector;
import com.ecommerce.inventory.worker.ExportSequencer;
import com.ecommerce.inventory.worker.ReadinessPublisher;
import com.ecommerce.inventory.worker.Reconciliation;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the docs/contract/code audit of the Inventory Service (ADR-028): identity
 * checks on non-canonical paths, the export watermark after a full retention purge, Reserve under
 * a duplicate order, replayed Refund Scope registration, the availability audit and the
 * snapshot-session expiry codes.
 */
class AuditCorrectionsIT extends AbstractIntegrationTest {

    private static final String EXPORT = "/exports/inventory/v1";
    private static final String WF = "/internal/inventory/v1";

    @Autowired
    Reconciliation reconciliation;
    @Autowired
    AvailabilityProjector projector;
    @Autowired
    ReadinessPublisher readiness;
    @Autowired
    ExportSequencer sequencer;

    // ── Identity checks on the decoded path ───────────────────────────────────

    @Test
    void encodedOrParameterisedPathsStillPassThroughTheIdentityChecks() throws Exception {
        for (String path : List.of("/api/v1/admin/inventor%79/warehouses",
                "/api/v1/admin/inventory;x=1/warehouses", "/%61pi/v1/admin/inventory/warehouses")) {
            MvcResult notAdmin = mvc.perform(MockMvcRequestBuilders.get(URI.create(path))
                    .header("X-User-Id", "user-1").header("X-User-Roles", "USER")).andReturn();
            assertThat(notAdmin.getResponse().getStatus()).as(path).isEqualTo(403);
            MvcResult anonymous = mvc.perform(MockMvcRequestBuilders.get(URI.create(path))).andReturn();
            assertThat(anonymous.getResponse().getStatus()).as(path).isEqualTo(401);
        }
        for (String path : List.of("/internal/inventor%79/v1/allocation-previews",
                "/internal/inventory;x=1/v1/allocation-previews")) {
            MvcResult noIdentity = mvc.perform(MockMvcRequestBuilders.post(URI.create(path))
                    .header("X-Correlation-ID", "corr-integration-test")
                    .header("X-Causation-ID", "cause-integration-test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"lines\":[{\"line_reference\":\"s\",\"sku_id\":\"s\",\"quantity\":1}]}")).andReturn();
            assertThat(noIdentity.getResponse().getStatus()).as(path).isEqualTo(401);
        }
    }

    // ── Export watermark after retention ──────────────────────────────────────

    @Test
    void snapshotWatermarkNeverFallsBelowTheRetentionFloor() throws Exception {
        String wh = createWarehouse("Main", 0);
        service("search-service", HttpMethod.PUT, EXPORT + "/search-projection-readiness/sku-a",
                Map.of("catalog_revision", 1), null);
        receive(wh, "sku-a", 1);
        projector.runOnce();
        readiness.runOnce();
        sequencer.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM published_event", Integer.class)).isPositive();

        jdbc.update("UPDATE published_event SET created_at = now() - interval '31 days'");
        reconciliation.applyRetention();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM published_event", Integer.class)).isZero();
        long floor = jdbc.queryForObject("SELECT retention_floor FROM export_retention WHERE id = 1", Long.class);
        assertThat(floor).isPositive();

        JsonNode session = service("search-service", HttpMethod.POST, EXPORT + "/snapshot-sessions", null, null).body();
        assertThat(session.path("export_watermark").asLong()).isEqualTo(floor);
        Response changes = service("search-service", HttpMethod.GET,
                EXPORT + "/changes?after_sequence=" + floor, null, null);
        assertThat(changes.status()).isEqualTo(200);
        assertThat(changes.body().path("latest_sequence").asLong()).isEqualTo(floor);
        assertThat(changes.body().path("items")).isEmpty();
        assertThat(service("search-service", HttpMethod.GET, EXPORT + "/changes?after_sequence=0", null, null)
                .status()).isEqualTo(410);
    }

    @Test
    void anExpiredSnapshotSessionIsGoneFirstAndNotFoundAfterRetentionPurgesIt() throws Exception {
        JsonNode session = service("search-service", HttpMethod.POST, EXPORT + "/snapshot-sessions", null, null).body();
        String page = EXPORT + "/snapshot-sessions/" + session.path("snapshot_id").asText() + "/sku-availabilities";
        assertThat(service("search-service", HttpMethod.GET, page, null, null).status()).isEqualTo(200);

        jdbc.update("UPDATE snapshot_session SET expires_at = now() - interval '1 minute'");
        Response expired = service("search-service", HttpMethod.GET, page, null, null);
        assertThat(expired.status()).isEqualTo(410);
        assertThat(expired.body().path("code").asText()).isEqualTo("SNAPSHOT_SESSION_EXPIRED");

        reconciliation.applyRetention();
        Response purged = service("search-service", HttpMethod.GET, page, null, null);
        assertThat(purged.status()).isEqualTo(404);
        assertThat(purged.body().path("code").asText()).isEqualTo("NOT_FOUND");
    }

    // ── Reserve and Refund Scope registration ─────────────────────────────────

    @Test
    void concurrentReservesOfOneOrderUnderDifferentCommandIdsNeverAnswerRejectAll() throws Exception {
        String wh = createWarehouse("Main", 0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                String sku = "dup-sku-" + round;
                String order = "dup-order-" + round;
                receive(wh, sku, 1);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Response>> calls = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    calls.add(pool.submit(() -> {
                        start.await();
                        return reserve(order, "l1", wh, sku, "1");
                    }));
                }
                start.countDown();
                int reserved = 0;
                int duplicates = 0;
                for (Future<Response> call : calls) {
                    Response response = call.get();
                    if (response.status() == 200) {
                        assertThat(response.body().path("outcome").asText()).as("round " + round)
                                .isEqualTo("RESERVED");
                        reserved++;
                    } else {
                        assertThat(response.body().path("code").asText()).as("round " + round)
                                .isEqualTo("RESERVATION_ALREADY_EXISTS");
                        duplicates++;
                    }
                }
                assertThat(reserved).as("round " + round).isEqualTo(1);
                assertThat(duplicates).as("round " + round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void replayedScopeRegistrationReportsTheCurrentScopeRevision() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 5);
        reserve("order-1", "l1", wh, "sku-a", "2");
        workflow(HttpMethod.POST, WF + "/reservations/order-1/commit", Map.of("payment_reference", "p"), key());
        dispatch("order-1", "l1", wh, "sku-a", 2);
        Map<String, Object> body = Map.of("order_id", "order-1", "refund_request_id", "rr-1", "lines",
                List.of(Map.of("order_line_id", "l1", "sku_id", "sku-a", "expected_quantity", 2)));
        JsonNode first = workflow(HttpMethod.POST, WF + "/refund-scopes", body, key()).body();
        assertThat(first.path("scope_revision").asLong()).isEqualTo(1);
        String scopeId = first.path("refund_scope_id").asText();

        admin(HttpMethod.POST, "/api/v1/admin/inventory/return-receipts", Map.of("refund_scope_id", scopeId,
                "order_line_id", "l1", "actual_sku_id", "sku-a", "warehouse_id", wh, "quantity", 1,
                "return_receipt_id", "ret-1", "return_receipt_line_id", "1", "package_reference", "pkg",
                "received_at", "2026-10-05T09:00:00Z", "evidence_reference", "e://r"), key());
        long current = workflow(HttpMethod.GET, WF + "/refund-scopes/" + scopeId, null, null).body()
                .path("revision").asLong();
        assertThat(current).isGreaterThan(1);

        JsonNode replay = workflow(HttpMethod.POST, WF + "/refund-scopes", body, key()).body();
        assertThat(replay.path("refund_scope_id").asText()).isEqualTo(scopeId);
        assertThat(replay.path("outcome").asText()).isEqualTo("REGISTERED");
        assertThat(replay.path("scope_revision").asLong()).isEqualTo(current);
    }

    // ── Availability audit ────────────────────────────────────────────────────

    @Test
    void availabilityAuditFlagsOnlyStatesThatDifferFromStockWithoutPendingWork() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 2);
        receive(wh, "sku-b", 1);
        projector.runOnce();

        Reconciliation.AvailabilityAudit clean = reconciliation.auditAvailability();
        assertThat(clean.checked()).isEqualTo(2);
        assertThat(clean.mismatchedSkus()).isEmpty();
        assertThat(clean.readinessSuppressed()).isEqualTo(2);

        service("search-service", HttpMethod.PUT, EXPORT + "/search-projection-readiness/sku-b",
                Map.of("catalog_revision", 1), null);
        assertThat(reconciliation.auditAvailability().readinessSuppressed()).isEqualTo(1);

        jdbc.update("UPDATE sku_availability_state SET is_in_stock = false WHERE sku_id = 'sku-a'");
        Reconciliation.AvailabilityAudit drift = reconciliation.auditAvailability();
        assertThat(drift.mismatchedSkus()).containsExactly("sku-a");

        jdbc.update("INSERT INTO sku_availability_dirty (sku_id, first_marked_at, mark_seq) "
                + "VALUES ('sku-a', now(), 1)");
        Reconciliation.AvailabilityAudit pending = reconciliation.auditAvailability();
        assertThat(pending.mismatchedSkus()).isEmpty();
        assertThat(pending.skippedPending()).isEqualTo(1);
    }

    @Test
    void availabilityAuditSkipsStatesCoveredByAnOpenWarehouseFanOut() throws Exception {
        String wh = createWarehouse("Main", 0);
        receive(wh, "sku-a", 1);
        projector.runOnce();
        JsonNode warehouse = admin(HttpMethod.GET, "/api/v1/admin/inventory/warehouses/" + wh, null, null).body();
        admin(HttpMethod.PATCH, "/api/v1/admin/inventory/warehouses/" + wh, Map.of(
                "expected_revision", warehouse.path("revision").asLong(), "operational_state", "OUTAGE",
                "reason", "outage", "evidence_reference", "e://outage"), key());

        // The warehouse is out, the stored state still says IN_STOCK: expected until the fan-out runs.
        Reconciliation.AvailabilityAudit during = reconciliation.auditAvailability();
        assertThat(during.mismatchedSkus()).isEmpty();
        assertThat(during.skippedPending()).isEqualTo(1);
    }
}
