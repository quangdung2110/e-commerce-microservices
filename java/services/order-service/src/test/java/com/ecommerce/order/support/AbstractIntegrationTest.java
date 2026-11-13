package com.ecommerce.order.support;

import com.ecommerce.order.messaging.FulfillmentIntake;
import com.ecommerce.order.messaging.InspectionIntake;
import com.ecommerce.order.messaging.PaymentIntake;
import com.ecommerce.order.service.OrderActivitiesImpl;
import com.ecommerce.order.web.dto.CheckoutDtos.CheckoutOrderSubmissionRequest;
import com.ecommerce.order.worker.Reconcilers;
import com.ecommerce.order.worker.WorkflowDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.temporal.testing.TestWorkflowEnvironment;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Real PostgreSQL (Testcontainers or {@code TEST_DB_URL}) + in-memory Temporal + contract
 * fakes. Each test uses its own buyer/checkout identities, so no truncation is needed and
 * the shared Temporal environment keeps running between tests.
 */
@SpringBootTest(properties = {
        "order.temporal.enabled=false",
        "order.kafka.consumers-enabled=false",
        "order.redis-mirror.enabled=false",
        "order.workers.enabled=true",
        "order.workers.reconcile-interval=PT1H",
        "order.checkout.submit-wait=20s",
        "spring.kafka.bootstrap-servers=localhost:1"})
@AutoConfigureMockMvc
@Import(TestTemporalConfig.class)
public abstract class AbstractIntegrationTest {

    private static final String EXTERNAL_DB_URL = System.getenv("TEST_DB_URL");
    private static final PostgreSQLContainer<?> POSTGRES = EXTERNAL_DB_URL == null
            ? new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("order_service") : null;
    private static final AtomicLong EVENT_VERSION = new AtomicLong(10);

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
    protected ObjectMapper mapper;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected FakeRemotes fakes;
    @Autowired
    protected TestWorkflowEnvironment temporal;
    @SpyBean
    protected OrderActivitiesImpl orderActivities;
    @Autowired
    protected PaymentIntake payments;
    @Autowired
    protected FulfillmentIntake fulfillment;
    @Autowired
    protected InspectionIntake inspections;
    @Autowired
    protected WorkflowDispatcher dispatcher;
    @Autowired
    protected Reconcilers reconcilers;
    @Autowired
    protected io.micrometer.core.instrument.MeterRegistry meterRegistry;

    // ------------------------------------------------------------------ HTTP

    public record Response(int status, JsonNode body, MvcResult raw) {
    }

    protected Response call(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(30_000);
            result = mvc.perform(MockMvcRequestBuilders.asyncDispatch(result)).andReturn();
        }
        String content = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Response(result.getResponse().getStatus(), content.isEmpty() ? null : mapper.readTree(content), result);
    }

    protected Response submit(CheckoutOrderSubmissionRequest request) throws Exception {
        return call(MockMvcRequestBuilders.post("/internal/v1/checkout-order-submissions")
                .header("X-Service-Identity", "checkout-service")
                .header("Idempotency-Key", request.checkoutToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)));
    }

    protected MockHttpServletRequestBuilder buyer(MockHttpServletRequestBuilder request, String userId) {
        return request.header("X-User-Id", userId);
    }

    protected MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("X-User-Id", "99999999-9999-4999-8999-999999999999").header("X-User-Roles", "USER,ADMIN");
    }

    protected Response buyerPost(String path, String userId, Object body) throws Exception {
        return call(buyer(MockMvcRequestBuilders.post(path), userId).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    protected Response buyerGet(String path, String userId) throws Exception {
        return call(buyer(MockMvcRequestBuilders.get(path), userId));
    }

    protected Response adminPost(String path, Object body) throws Exception {
        return call(admin(MockMvcRequestBuilders.post(path)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    // ------------------------------------------------------------------ waiting

    protected void await(String what, Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    protected String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    protected void awaitStatus(long orderId, String status) {
        await("order " + orderId + " " + status, () -> status.equals(orderStatus(orderId)));
    }

    protected String operationStatus(String operationId) {
        return jdbc.queryForObject("SELECT status FROM order_operations WHERE operation_id = ?::uuid", String.class,
                operationId);
    }

    protected Map<String, Object> order(long orderId) {
        return jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
    }

    protected List<Long> lineIds(long orderId) {
        return jdbc.queryForList("SELECT id FROM order_item_lines WHERE order_id = ? ORDER BY id", Long.class, orderId);
    }

    protected String activeTxn(long orderId) {
        return jdbc.queryForObject("SELECT active_payment_transaction_id FROM orders WHERE id = ?", String.class, orderId);
    }

    protected List<JsonNode> outbox(long orderId, String eventType) throws Exception {
        List<String> rows = jdbc.queryForList("""
                SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND event_type = ? ORDER BY created_at
                """, String.class, Long.toString(orderId), eventType);
        List<JsonNode> events = new java.util.ArrayList<>();
        for (String row : rows) {
            events.add(mapper.readTree(row));
        }
        return events;
    }

    protected List<String> notificationTemplates(long orderId) {
        return jdbc.queryForList("""
                SELECT template_id FROM order_notification_commands WHERE order_id = ? ORDER BY notification_sequence
                """, String.class, orderId);
    }

    /** Submits, waits for 201 and returns the order ID. */
    protected long placeOrder(CheckoutOrderSubmissionRequest request) throws Exception {
        Response response = submit(request);
        if (response.status() != 201) {
            throw new AssertionError("Submission failed " + response.status() + " " + response.body());
        }
        return Long.parseLong(response.body().path("order_id").asText());
    }

    // ------------------------------------------------------------------ Payment facts (contracts/events/payment/v2)

    protected String paymentEvent(long orderId, String kind, String txn, int attempt, long amount,
                                  Map<String, Object> extra) throws Exception {
        String schema = switch (kind) {
            case "transaction.succeeded" -> "payment-succeeded";
            case "transaction.failed" -> "payment-failed";
            case "transaction.expired" -> "payment-expired";
            case "transaction.canceled" -> "payment-canceled";
            default -> kind.replace('.', '-');
        };
        ObjectNode event = mapper.createObjectNode();
        event.put("specversion", "1.0");
        event.put("id", UUID.randomUUID().toString());
        event.put("type", "com.ecommerce.payment." + kind + ".v2");
        event.put("source", "/services/payment");
        event.put("subject", "payments/" + txn);
        event.put("time", Instant.now().toString());
        event.put("datacontenttype", "application/json");
        event.put("dataschema", "/contracts/events/payment/v2/schemas/" + schema + ".schema.json");
        ObjectNode data = event.putObject("data");
        data.put("schema_version", 2);
        data.put("order_id", Long.toString(orderId));
        data.put("payment_transaction_id", txn);
        data.put("payment_attempt_number", attempt);
        data.put("financial_state_version", EVENT_VERSION.incrementAndGet());
        data.put("occurred_at", Instant.ofEpochMilli(temporal.currentTimeMillis()).toString());
        if (kind.startsWith("transaction.")) {
            data.putObject("amount").put("amount_minor", amount).put("currency", "VND");
        }
        extra.forEach((k, v) -> data.set(k, mapper.valueToTree(v)));
        return mapper.writeValueAsString(event);
    }

    protected void paymentSucceeded(long orderId) throws Exception {
        String txn = activeTxn(orderId);
        int attempt = jdbc.queryForObject("SELECT active_payment_attempt_number FROM orders WHERE id = ?", Integer.class,
                orderId);
        long amount = jdbc.queryForObject("SELECT final_amount_minor FROM orders WHERE id = ?", Long.class, orderId);
        payments.handle(Long.toString(orderId), paymentEvent(orderId, "transaction.succeeded", txn, attempt, amount, Map.of()));
    }

    protected void refundEvent(long orderId, String kind, Map<String, Object> extra) throws Exception {
        Map<String, Object> refund = jdbc.queryForMap("""
                SELECT c.refund_transaction_id, c.refund_amount_locked_minor, o.captured_payment_transaction_id,
                       o.active_payment_attempt_number
                FROM order_refund_cases c JOIN orders o ON o.id = c.order_id WHERE c.order_id = ?
                """, orderId);
        Map<String, Object> data = new java.util.HashMap<>(extra);
        data.put("refund_transaction_id", refund.get("refund_transaction_id"));
        data.put("refund_amount", Map.of("amount_minor", refund.get("refund_amount_locked_minor"), "currency", "VND"));
        payments.handle(Long.toString(orderId), paymentEvent(orderId, kind,
                (String) refund.get("captured_payment_transaction_id"),
                ((Number) refund.get("active_payment_attempt_number")).intValue(), 0, data));
    }

    // ------------------------------------------------------------------ Inventory facts and Admin delivery confirmation

    protected void dispatchAll(long orderId) throws Exception {
        for (Map<String, Object> line : jdbc.queryForList("""
                SELECT id, sku_id, quantity, allocated_warehouse_id FROM order_item_lines WHERE order_id = ? ORDER BY id
                """, orderId)) {
            dispatch(orderId, ((Number) line.get("id")).longValue(), (String) line.get("allocated_warehouse_id"),
                    (String) line.get("sku_id"), ((Number) line.get("quantity")).intValue());
        }
    }

    protected void dispatch(long orderId, long lineId, String warehouse, String sku, int quantity) throws Exception {
        ObjectNode event = inventoryEnvelope("com.ecommerce.inventory.reservation.line-dispatched.v1",
                "orders/" + orderId + "/lines/" + lineId, "reservation-line", "line-" + lineId);
        event.put("dataschema", "/contracts/events/inventory/v1/schemas/reservation-line-dispatched.schema.json");
        ObjectNode data = event.putObject("data");
        data.put("order_id", Long.toString(orderId)).put("order_line_id", Long.toString(lineId)).put("sku_id", sku)
                .put("quantity", quantity).put("warehouse_id", warehouse).put("handover_reference", "HO-" + lineId)
                .put("handed_over_at", Instant.now().toString()).put("reservation_revision", 3);
        fulfillment.handleDispatch(Long.toString(orderId), mapper.writeValueAsString(event));
    }

    /** Admin delivery confirmation (ADR-021) of every line of the order in one call. */
    protected void deliverAll(long orderId) throws Exception {
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        for (Long lineId : lineIds(orderId)) {
            lines.add(deliveryLine(lineId, "TRK-" + orderId + "-" + lineId));
        }
        Response response = confirmDelivery(orderId, lines);
        if (response.status() != 200) {
            throw new AssertionError("Delivery confirmation failed " + response.status() + " " + response.body());
        }
    }

    protected Map<String, Object> deliveryLine(long lineId, String reference) {
        return Map.of("order_line_id", Long.toString(lineId), "delivered_at", Instant.now().toString(),
                "delivery_reference", reference);
    }

    protected Response confirmDelivery(long orderId, List<Map<String, Object>> lines) throws Exception {
        return call(admin(MockMvcRequestBuilders.post("/api/v1/admin/orders/" + orderId + "/delivery-confirmations"))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("lines", lines))));
    }

    protected void inspectionCompleted(long orderId, long[][] lines) throws Exception {
        Map<String, Object> refundCase = jdbc.queryForMap(
                "SELECT inventory_refund_scope_id, refund_request_id FROM order_refund_cases WHERE order_id = ?", orderId);
        ObjectNode event = inventoryEnvelope("com.ecommerce.inventory.refund.all-items-inspection-completed.v1",
                "orders/" + orderId + "/refund-scopes/" + refundCase.get("inventory_refund_scope_id"), "refund-scope",
                (String) refundCase.get("inventory_refund_scope_id"));
        event.put("dataschema", "/contracts/events/inventory/v1/schemas/all-items-inspection-completed.schema.json");
        ObjectNode data = event.putObject("data");
        data.put("refund_scope_id", (String) refundCase.get("inventory_refund_scope_id"))
                .put("refund_request_id", (String) refundCase.get("refund_request_id"))
                .put("order_id", Long.toString(orderId)).put("scope_revision", 4);
        ArrayNode array = data.putArray("lines");
        for (long[] line : lines) {
            array.addObject().put("order_line_id", Long.toString(line[0])).put("sku_id", "SKU")
                    .put("expected_quantity", line[1]).put("received_quantity", line[1])
                    .put("passed_quantity", line[2]).put("failed_quantity", line[1] - line[2]);
        }
        inspections.handle(Long.toString(orderId), mapper.writeValueAsString(event));
    }

    private ObjectNode inventoryEnvelope(String type, String subject, String aggregateType, String aggregateId) {
        ObjectNode event = mapper.createObjectNode();
        event.put("specversion", "1.0").put("id", UUID.randomUUID().toString()).put("source", "/services/inventory")
                .put("type", type).put("subject", subject).put("time", Instant.now().toString())
                .put("datacontenttype", "application/json").put("correlationid", "corr-12345678")
                .put("causationid", "cause-12345678").put("sequence", 1).put("aggregatetype", aggregateType)
                .put("aggregateid", aggregateId).put("aggregaterevision", 3);
        return event;
    }

    /** Places an order, pays it and drives post-payment repair to PROCESSING. */
    protected long paidOrder(CheckoutOrderSubmissionRequest request) throws Exception {
        long orderId = placeOrder(request);
        paymentSucceeded(orderId);
        awaitStatus(orderId, "PROCESSING");
        return orderId;
    }

    /** Places, pays, dispatches and delivers every line: COMPLETED. */
    protected long completedOrder(CheckoutOrderSubmissionRequest request) throws Exception {
        long orderId = paidOrder(request);
        dispatchAll(orderId);
        deliverAll(orderId);
        awaitStatus(orderId, "COMPLETED");
        return orderId;
    }

    protected void validateAgainstContracts(long orderId) throws Exception {
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
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            JsonNode event = mapper.readTree((String) row.get("payload"));
            JsonSchema schema = factory.getSchema(SchemaLocation.of(prefix + schemas.get((String) row.get("event_type"))));
            assertThat(schema.validate(event)).as(row.get("event_type") + " " + event).isEmpty();
        }
    }
}
