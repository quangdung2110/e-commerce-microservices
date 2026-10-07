package com.ecommerce.inventory.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;
import java.util.UUID;

/**
 * Real PostgreSQL through Testcontainers (or {@code TEST_DB_URL}); the schema comes from
 * the service's Flyway migrations. Workers are disabled and driven explicitly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final String EXTERNAL_DB_URL = System.getenv("TEST_DB_URL");

    private static final PostgreSQLContainer<?> POSTGRES = EXTERNAL_DB_URL == null
            ? new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("inventory_service")
            : null;

    static {
        if (POSTGRES != null) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_DB_URL);
        }
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("""
                TRUNCATE published_event, outbox_event, snapshot_session, readiness_publication_task,
                         search_projection_readiness, availability_fanout_job, sku_availability_dirty,
                         sku_availability_state, operation_identity, command_execution, audit_entry,
                         disposition_reversal, quarantine_disposition, inspection_result, return_receipt_line,
                         refund_scope_line, refund_scope, physical_dispatch, reservation_line, order_reservation,
                         stock_movement, stock_position, warehouse
                RESTART IDENTITY CASCADE""");
        jdbc.execute("ALTER SEQUENCE export_sequence RESTART WITH 1");
        jdbc.update("UPDATE export_retention SET retention_floor = 0");
        jdbc.update("UPDATE worker_lease SET holder = NULL, fencing_token = 0, expires_at = 'epoch'");
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    protected record Response(int status, JsonNode body) {
    }

    protected static String key() {
        return "cmd-" + UUID.randomUUID();
    }

    protected Response workflow(HttpMethod method, String path, Object body, String key) throws Exception {
        return service("order-service", method, path, body, key);
    }

    protected Response service(String identity, HttpMethod method, String path, Object body, String key)
            throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path)
                .header("X-Correlation-ID", "corr-integration-test")
                .header("X-Causation-ID", "cause-integration-test");
        if (identity != null) {
            request.header("X-Service-Identity", identity);
        }
        return send(request, body, key);
    }

    protected Response admin(HttpMethod method, String path, Object body, String key) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path)
                .header("X-User-Id", "11111111-1111-1111-1111-111111111111")
                .header("X-User-Roles", "ADMIN")
                .header("X-User-Session-Id", "session-1")
                .header("X-Correlation-ID", "corr-admin-test")
                .header("X-Causation-ID", key == null ? "cause-admin-test" : key);
        return send(request, body, key);
    }

    private Response send(MockHttpServletRequestBuilder request, Object body, String key) throws Exception {
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String s ? s : objectMapper.writeValueAsString(body));
        }
        MvcResult result = mvc.perform(request).andReturn();
        String content = result.getResponse().getContentAsString();
        return new Response(result.getResponse().getStatus(),
                content.isEmpty() ? objectMapper.nullNode() : objectMapper.readTree(content));
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    protected String createWarehouse(String name, int priority) throws Exception {
        Response response = admin(HttpMethod.POST, "/api/v1/admin/inventory/warehouses", Map.of(
                "name", name,
                "address", Map.of("line1", "1 Test Street", "locality", "Hanoi", "country_code", "VN"),
                "is_active", true,
                "operational_state", "NORMAL",
                "fulfillment_priority", priority,
                "reason", "open warehouse",
                "evidence_reference", "evidence://warehouse"), key());
        return response.body().path("warehouse").path("warehouse_id").asText();
    }

    protected Response receive(String warehouseId, String skuId, long quantity) throws Exception {
        return admin(HttpMethod.POST, "/api/v1/admin/inventory/stock-receipts", Map.of(
                "warehouse_id", warehouseId,
                "sku_id", skuId,
                "quantity", quantity,
                "receipt_id", "rcpt-" + UUID.randomUUID(),
                "receipt_line_id", "1",
                "reason", "SUPPLIER_RECEIPT",
                "goods_receipt_reference", "grn-1",
                "evidence_reference", "evidence://grn-1"), key());
    }

    protected Response reserve(String orderId, String... lineWarehouseSkuQuantity) throws Exception {
        java.util.List<Map<String, Object>> lines = new java.util.ArrayList<>();
        for (int i = 0; i < lineWarehouseSkuQuantity.length; i += 4) {
            lines.add(Map.of("order_line_id", lineWarehouseSkuQuantity[i],
                    "warehouse_id", lineWarehouseSkuQuantity[i + 1],
                    "sku_id", lineWarehouseSkuQuantity[i + 2],
                    "quantity", Long.parseLong(lineWarehouseSkuQuantity[i + 3])));
        }
        return workflow(HttpMethod.POST, "/internal/inventory/v1/reservations",
                Map.of("order_id", orderId, "allocation_plan_id", "plan-" + orderId, "lines", lines), key());
    }

    protected JsonNode position(String warehouseId, String skuId) throws Exception {
        return admin(HttpMethod.GET, "/api/v1/admin/inventory/stock-positions/" + warehouseId + "/" + skuId,
                null, null).body();
    }

    protected Response dispatch(String orderId, String lineId, String warehouseId, String skuId, long quantity)
            throws Exception {
        return admin(HttpMethod.POST, "/api/v1/admin/inventory/physical-dispatches", Map.of(
                "dispatch_id", "dsp-" + UUID.randomUUID(),
                "dispatch_line_id", "1",
                "order_id", orderId,
                "order_line_id", lineId,
                "warehouse_id", warehouseId,
                "sku_id", skuId,
                "quantity", quantity,
                "handed_over_at", "2026-10-05T08:00:00Z",
                "handover_reference", "handover-" + lineId,
                "evidence_reference", "evidence://handover"), key());
    }
}
