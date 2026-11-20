package com.ecommerce.order.messaging;

import com.ecommerce.order.common.Ids;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.domain.FulfillmentStatus;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.service.OrderConvergence;
import com.ecommerce.order.store.InboxStore;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.OrderStore.LineRow;
import com.ecommerce.order.store.OrderStore.OrderRow;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Physical dispatch (WF-ORDER-006). The Inventory fact changes one line FSM inside the inbox
 * transaction and maps only to DISPATCHED: it never delivers a line or completes an order.
 * DELIVERED comes solely from the Admin delivery confirmation (WF-ORDER-006a, ADR-021).
 */
@Component
public class FulfillmentIntake {

    public static final String INVENTORY_SOURCE = "/services/inventory";
    public static final String DISPATCHED_TYPE = "com.ecommerce.inventory.reservation.line-dispatched.v1";

    private static final Logger log = LoggerFactory.getLogger(FulfillmentIntake.class);
    private static final Set<OrderStatus> SHIPPABLE = EnumSet.of(OrderStatus.PAID, OrderStatus.PROCESSING,
            OrderStatus.FINANCIAL_RECONCILIATION_REQUIRED, OrderStatus.COMPLETED, OrderStatus.RETURN_REQUESTED,
            OrderStatus.REFUNDED);

    private final InboxStore inbox;
    private final OrderStore orders;
    private final TransactionTemplate tx;
    private final Json json;
    private final MeterRegistry meters;

    public FulfillmentIntake(InboxStore inbox, OrderStore orders, TransactionTemplate tx, Json json,
                             MeterRegistry meters) {
        this.inbox = inbox;
        this.orders = orders;
        this.tx = tx;
        this.json = json;
        this.meters = meters;
    }

    public void handleDispatch(String key, String value) {
        CloudEvent event = CloudEvent.parse(json.mapper(), value);
        if (event == null || !INVENTORY_SOURCE.equals(event.source()) || !DISPATCHED_TYPE.equals(event.type())) {
            return;
        }
        JsonNode data = event.data();
        Long orderId = data == null ? null : Ids.parseCanonical(data.path("order_id").asText(null));
        Long lineId = data == null ? null : Ids.parseCanonical(data.path("order_line_id").asText(null));
        String stream = "reservation-line:" + (data == null ? "invalid" : data.path("order_line_id").asText("invalid"));
        long revision = data == null ? 0 : data.path("reservation_revision").asLong(0);
        String eventId = event.hasUuidId() ? event.id() : "invalid-" + event.sha256();
        tx.executeWithoutResult(s -> {
            boolean known = orderId != null && orders.lock(orderId).isPresent();
            String problem = !event.hasUuidId() || !event.dataIsObject() || revision < 1 ? "INVALID_ENVELOPE"
                    : !known ? "UNKNOWN_ORDER" : null;
            InboxStore.InsertResult inserted = inbox.insert(INVENTORY_SOURCE, eventId, event.type(),
                    known ? orderId : null, stream, revision, PaymentIntake.digest(event), json.write(event.raw()),
                    problem == null ? "RECEIVED" : "REJECTED");
            if (inserted != InboxStore.InsertResult.INSERTED) {
                return;
            }
            if (problem != null) {
                inbox.setStatus(INVENTORY_SOURCE, eventId, "REJECTED", problem);
                return;
            }
            String result = applyDispatch(orderId, lineId, data, UUID.fromString(eventId));
            inbox.setStatus(INVENTORY_SOURCE, eventId, result.startsWith("REJECTED:") ? "REJECTED" : result,
                    result.startsWith("REJECTED:") ? result.substring(9) : null);
        });
    }

    private String applyDispatch(long orderId, Long lineId, JsonNode data, UUID eventId) {
        OrderRow order = orders.find(orderId).orElseThrow();
        if (!SHIPPABLE.contains(order.status())) {
            log.error("Dispatch for order {} in state {} quarantined", orderId, order.status());
            return "REJECTED:ORDER_NOT_SHIPPABLE";
        }
        Optional<LineRow> line = lineId == null ? Optional.empty() : orders.line(orderId, lineId);
        if (line.isEmpty() || !line.get().warehouseId().equals(data.path("warehouse_id").asText())
                || !line.get().skuId().equals(data.path("sku_id").asText())
                || line.get().quantity() != data.path("quantity").asInt()) {
            meters.counter("order.fulfillment.mapping_mismatch", "kind", "dispatch").increment();
            log.error("Dispatch mapping mismatch order={} line={}", orderId, lineId);
            return "REJECTED:ALLOCATION_MISMATCH";
        }
        if (line.get().fulfillmentStatus() != FulfillmentStatus.PROCESSING) {
            return "APPLIED";
        }
        orders.markDispatched(lineId, eventId, data.path("reservation_revision").asLong(),
                Instant.parse(data.path("handed_over_at").asText()));
        orders.refreshItemStatus(line.get().orderItemId());
        meters.counter("order.fulfillment.dispatched").increment();
        return "APPLIED";
    }
}
