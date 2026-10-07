package com.ecommerce.inventory.service;

import com.ecommerce.inventory.common.Ids;
import com.ecommerce.inventory.store.OutboxStore;
import com.ecommerce.inventory.store.RefundStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Writes the three Release 1 public facts (interfaces.md §6) to the outbox as structured
 * CloudEvents. {@code sequence} is added later by the export sequencer (ADR-022).
 */
@Component
public class EventFactory {

    public static final String STOCK_TOPIC = "t.inventory.stock.events.v1";
    public static final String ORDER_TOPIC = "t.inventory.order.events.v1";

    public static final String SKU_AVAILABILITY_CHANGED = "com.ecommerce.inventory.sku-availability.changed.v1";
    public static final String LINE_DISPATCHED = "com.ecommerce.inventory.reservation.line-dispatched.v1";
    public static final String INSPECTION_COMPLETED = "com.ecommerce.inventory.refund.all-items-inspection-completed.v1";

    private static final String SCHEMA_BASE = "/contracts/events/inventory/v1/schemas/";

    private final OutboxStore outbox;
    private final ObjectMapper objectMapper;

    public EventFactory(OutboxStore outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    public void skuAvailabilityChanged(String skuId, boolean inStock, long revision, Instant observedAt,
                                       String correlationId, String causationId, Instant now) {
        ObjectNode data = objectMapper.createObjectNode()
                .put("sku_id", skuId)
                .put("is_in_stock", inStock)
                .put("availability_revision", revision)
                .put("observed_at", observedAt.toString());
        UUID id = Ids.uuid();
        ObjectNode event = envelope(id, SKU_AVAILABILITY_CHANGED, "sku-availability/" + skuId,
                "sku-availability-changed.schema.json", correlationId, causationId, "sku-availability", skuId,
                revision, now, data);
        outbox.append(id, STOCK_TOPIC, skuId, SKU_AVAILABILITY_CHANGED, skuId, event, now);
    }

    /** {@code aggregaterevision} = reservation root revision after the dispatch (ADR-027). */
    public void lineDispatched(String orderId, String orderLineId, String skuId, long quantity, String warehouseId,
                               String handoverReference, Instant handedOverAt, long reservationRevision,
                               String correlationId, String causationId, Instant now) {
        ObjectNode data = objectMapper.createObjectNode()
                .put("order_id", orderId)
                .put("order_line_id", orderLineId)
                .put("sku_id", skuId)
                .put("quantity", quantity)
                .put("warehouse_id", warehouseId)
                .put("handover_reference", handoverReference)
                .put("handed_over_at", handedOverAt.toString())
                .put("reservation_revision", reservationRevision);
        UUID id = Ids.uuid();
        ObjectNode event = envelope(id, LINE_DISPATCHED, "orders/" + orderId + "/lines/" + orderLineId,
                "reservation-line-dispatched.schema.json", correlationId, causationId, "reservation-line",
                orderId + ":" + orderLineId, reservationRevision, now, data);
        outbox.append(id, ORDER_TOPIC, orderId, LINE_DISPATCHED, null, event, now);
    }

    public void inspectionCompleted(RefundStore.Scope scope, long scopeRevision, List<RefundStore.ScopeLine> lines,
                                    String correlationId, String causationId, Instant now) {
        ObjectNode data = objectMapper.createObjectNode()
                .put("refund_scope_id", scope.refundScopeId())
                .put("refund_request_id", scope.refundRequestId())
                .put("order_id", scope.orderId())
                .put("scope_revision", scopeRevision);
        ArrayNode array = data.putArray("lines");
        lines.forEach(line -> array.addObject()
                .put("order_line_id", line.orderLineId())
                .put("sku_id", line.skuId())
                .put("expected_quantity", line.expected())
                .put("received_quantity", line.received())
                .put("passed_quantity", line.passed())
                .put("failed_quantity", line.failed()));
        UUID id = Ids.uuid();
        ObjectNode event = envelope(id, INSPECTION_COMPLETED,
                "orders/" + scope.orderId() + "/refund-scopes/" + scope.refundScopeId(),
                "all-items-inspection-completed.schema.json", correlationId, causationId, "refund-scope",
                scope.refundScopeId(), scopeRevision, now, data);
        outbox.append(id, ORDER_TOPIC, scope.orderId(), INSPECTION_COMPLETED, null, event, now);
    }

    private ObjectNode envelope(UUID id, String type, String subject, String schema, String correlationId,
                                String causationId, String aggregateType, String aggregateId, long aggregateRevision,
                                Instant time, ObjectNode data) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("specversion", "1.0");
        event.put("id", id.toString());
        event.put("type", type);
        event.put("source", "/services/inventory");
        event.put("subject", subject);
        event.put("time", time.toString());
        event.put("datacontenttype", "application/json");
        event.put("dataschema", SCHEMA_BASE + schema);
        event.put("correlationid", correlationId);
        event.put("causationid", causationId);
        event.put("aggregatetype", aggregateType);
        event.put("aggregateid", aggregateId);
        event.put("aggregaterevision", aggregateRevision);
        event.set("data", data);
        return event;
    }
}
