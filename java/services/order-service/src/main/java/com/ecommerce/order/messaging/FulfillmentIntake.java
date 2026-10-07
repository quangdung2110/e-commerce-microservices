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
import java.util.regex.Pattern;

/**
 * Physical dispatch (WF-ORDER-006) and verified Delivery proof (WF-ORDER-006a). Both
 * change one line FSM inside the inbox transaction: dispatch maps only to DISPATCHED;
 * only a Delivery proof may set DELIVERED, and a proof arriving before its dispatch is
 * parked PENDING_PREREQUISITE and replayed by the dispatch of that same line.
 */
@Component
public class FulfillmentIntake {

    public static final String INVENTORY_SOURCE = "/services/inventory";
    public static final String DELIVERY_SOURCE = "/services/delivery";
    public static final String DISPATCHED_TYPE = "com.ecommerce.inventory.reservation.line-dispatched.v1";
    public static final String DELIVERED_TYPE = "com.ecommerce.delivery.order-line.delivered.v1";

    private static final Logger log = LoggerFactory.getLogger(FulfillmentIntake.class);
    private static final Pattern SHA256 = Pattern.compile("^[A-Fa-f0-9]{64}$");
    private static final Set<OrderStatus> SHIPPABLE = EnumSet.of(OrderStatus.PAID, OrderStatus.PROCESSING,
            OrderStatus.FINANCIAL_RECONCILIATION_REQUIRED, OrderStatus.COMPLETED, OrderStatus.RETURN_REQUESTED,
            OrderStatus.REFUNDED);

    private final InboxStore inbox;
    private final OrderStore orders;
    private final OrderConvergence convergence;
    private final TransactionTemplate tx;
    private final Json json;
    private final MeterRegistry meters;

    public FulfillmentIntake(InboxStore inbox, OrderStore orders, OrderConvergence convergence,
                             TransactionTemplate tx, Json json, MeterRegistry meters) {
        this.inbox = inbox;
        this.orders = orders;
        this.convergence = convergence;
        this.tx = tx;
        this.json = json;
        this.meters = meters;
    }

    // ------------------------------------------------------------------ Inventory dispatch

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
        // A verified proof that arrived first is replayed now (ADR-015).
        for (InboxStore.InboxRow parked : inbox.lockParked(orderId, "PENDING_PREREQUISITE")) {
            CloudEvent proof = CloudEvent.parse(json.mapper(), parked.payloadJson());
            if (proof != null && lineId.equals(Ids.parseCanonical(proof.data().path("order_line_id").asText(null)))) {
                String result = applyDelivery(orderId, proof);
                inbox.setStatus(parked.source(), parked.eventId(), result.startsWith("REJECTED:") ? "REJECTED" : result,
                        result.startsWith("REJECTED:") ? result.substring(9) : null);
            }
        }
        convergence.tryComplete(orderId);
        return "APPLIED";
    }

    // ------------------------------------------------------------------ Delivery proof

    public void handleDelivery(String key, String value) {
        CloudEvent event = CloudEvent.parse(json.mapper(), value);
        if (event == null || !DELIVERY_SOURCE.equals(event.source()) || !DELIVERED_TYPE.equals(event.type())) {
            return;
        }
        JsonNode data = event.data();
        Long orderId = data == null ? null : Ids.parseCanonical(data.path("order_id").asText(null));
        String stream = "delivery-line:" + (data == null ? "invalid" : data.path("order_line_id").asText("invalid"));
        long revision = data == null ? 0 : data.path("aggregate_revision").asLong(0);
        String eventId = event.hasUuidId() ? event.id() : "invalid-" + event.sha256();
        tx.executeWithoutResult(s -> {
            boolean known = orderId != null && orders.lock(orderId).isPresent();
            String problem = !event.hasUuidId() || !event.dataIsObject() || revision < 1
                    || !SHA256.matcher(data.path("delivery_proof_sha256").asText("")).matches()
                    || data.path("delivery_proof_id").asText("").isEmpty() ? "INVALID_ENVELOPE"
                    : !known ? "UNKNOWN_ORDER" : null;
            InboxStore.InsertResult inserted = inbox.insert(DELIVERY_SOURCE, eventId, event.type(),
                    known ? orderId : null, stream, revision, PaymentIntake.digest(event), json.write(event.raw()),
                    problem == null ? "RECEIVED" : "REJECTED");
            if (inserted != InboxStore.InsertResult.INSERTED) {
                return;
            }
            if (problem != null) {
                inbox.setStatus(DELIVERY_SOURCE, eventId, "REJECTED", problem);
                return;
            }
            String result = applyDelivery(orderId, event);
            inbox.setStatus(DELIVERY_SOURCE, eventId, result.startsWith("REJECTED:") ? "REJECTED" : result,
                    result.startsWith("REJECTED:") ? result.substring(9) : null);
        });
    }

    /** Applies one proof; caller holds the order lock. Returns inbox status or REJECTED:reason. */
    String applyDelivery(long orderId, CloudEvent event) {
        JsonNode data = event.data();
        Long lineId = Ids.parseCanonical(data.path("order_line_id").asText(null));
        Optional<LineRow> line = lineId == null ? Optional.empty() : orders.line(orderId, lineId);
        if (line.isEmpty() || !line.get().warehouseId().equals(data.path("warehouse_id").asText())) {
            meters.counter("order.fulfillment.mapping_mismatch", "kind", "delivery").increment();
            return "REJECTED:ALLOCATION_MISMATCH";
        }
        String proofId = data.path("delivery_proof_id").asText();
        String proofSha = data.path("delivery_proof_sha256").asText().toLowerCase();
        Optional<LineRow> proofOwner = orders.lineByProof(proofId);
        if (proofOwner.isPresent() && proofOwner.get().id() != lineId) {
            log.error("Delivery proof {} reused for another line {}", proofId, lineId);
            return "REJECTED:PROOF_BOUND_TO_OTHER_LINE";
        }
        switch (line.get().fulfillmentStatus()) {
            case PROCESSING:
                return "PENDING_PREREQUISITE";
            case DELIVERED:
                return proofId.equals(line.get().deliveryProofId()) && proofSha.equals(line.get().deliveryProofSha256())
                        ? "APPLIED" : "REJECTED:CONFLICTING_PROOF";
            default:
                break;
        }
        orders.markDelivered(lineId, UUID.fromString(event.id()), proofId, proofSha,
                Instant.parse(data.path("delivered_at").asText()));
        orders.refreshItemStatus(line.get().orderItemId());
        convergence.tryComplete(orderId);
        meters.counter("order.fulfillment.delivered").increment();
        return "APPLIED";
    }

    /** Reconciler entry: replays proofs whose dispatch evidence now exists. */
    public void replayPrerequisites(long orderId) {
        tx.executeWithoutResult(s -> {
            orders.lock(orderId);
            for (InboxStore.InboxRow parked : inbox.lockParked(orderId, "PENDING_PREREQUISITE")) {
                CloudEvent proof = CloudEvent.parse(json.mapper(), parked.payloadJson());
                if (proof == null) {
                    inbox.setStatus(parked.source(), parked.eventId(), "REJECTED", "UNPARSEABLE_PARKED_EVENT");
                    continue;
                }
                String result = applyDelivery(orderId, proof);
                inbox.setStatus(parked.source(), parked.eventId(), result.startsWith("REJECTED:") ? "REJECTED" : result,
                        result.startsWith("REJECTED:") ? result.substring(9) : null);
            }
        });
    }
}
