package com.ecommerce.order.service;

import com.ecommerce.order.common.ApiException;
import com.ecommerce.order.common.ErrorCode;
import com.ecommerce.order.common.Ids;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.domain.FulfillmentStatus;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.OrderStore.LineRow;
import com.ecommerce.order.store.OrderStore.OrderRow;
import com.ecommerce.order.web.Caller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WF-ORDER-006a (ADR-021): the only way a line becomes DELIVERED. An Admin attests, per
 * dispatched line, when and by which carrier receipt/tracking reference the goods reached the
 * customer. The command is synchronous and idempotent by line state: it locks the order, applies
 * every requested line or none, then runs {@link OrderConvergence#tryComplete} so the last
 * confirmation completes the master and emits {@code order.completed.v1} in the same transaction.
 */
@Service
public class DeliveryConfirmationService {

    /** States in which a DISPATCHED line can exist and be confirmed. */
    private static final Set<OrderStatus> CONFIRMABLE = EnumSet.of(OrderStatus.PAID, OrderStatus.PROCESSING,
            OrderStatus.FINANCIAL_RECONCILIATION_REQUIRED);

    private final OrderStore orders;
    private final OrderConvergence convergence;
    private final TransactionTemplate tx;
    private final Json json;
    private final Clock clock;
    private final OrderProperties.Fulfillment config;
    private final MeterRegistry meters;

    public DeliveryConfirmationService(OrderStore orders, OrderConvergence convergence, TransactionTemplate tx,
                                       Json json, Clock clock, OrderProperties properties, MeterRegistry meters) {
        this.orders = orders;
        this.convergence = convergence;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.config = properties.fulfillment();
        this.meters = meters;
    }

    public ObjectNode confirm(String orderIdValue, Caller admin, JsonNode body) {
        Long orderId = Ids.parseCanonical(orderIdValue);
        if (orderId == null) {
            throw ApiException.notFound();
        }
        return tx.execute(s -> {
            OrderRow order = orders.lock(orderId).orElseThrow(ApiException::notFound);
            Map<Long, LineRow> byId = new HashMap<>();
            orders.lockLines(orderId).forEach(l -> byId.put(l.id(), l));
            List<Requested> requested = parse(body.path("lines"), byId);

            Instant now = clock.instant();
            List<ApiException.FieldError> invalid = new ArrayList<>();
            List<Requested> toApply = new ArrayList<>();
            ApiException conflict = null;
            for (Requested r : requested) {
                LineRow line = byId.get(r.lineId());
                switch (line.fulfillmentStatus()) {
                    case DELIVERED -> {
                        if (!r.reference().equals(line.deliveryReference())
                                || !r.deliveredAt().equals(line.deliveredAt())) {
                            conflict = conflict != null ? conflict : new ApiException(
                                    ErrorCode.DELIVERY_ALREADY_CONFIRMED, "Line " + r.lineId()
                                    + " was already confirmed with a different reference or time");
                        }
                    }
                    case PROCESSING -> conflict = conflict != null ? conflict : new ApiException(
                            ErrorCode.DELIVERY_REQUIRES_DISPATCH, "Line " + r.lineId()
                            + " has not been physically dispatched by Inventory yet");
                    case DISPATCHED -> {
                        if (line.dispatchedAt() != null && r.deliveredAt().isBefore(line.dispatchedAt())) {
                            invalid.add(new ApiException.FieldError("lines", "delivered_at of line " + r.lineId()
                                    + " is before its physical dispatch"));
                        } else if (r.deliveredAt().isAfter(now.plus(config.deliveredAtFutureSkew()))) {
                            invalid.add(new ApiException.FieldError("lines", "delivered_at of line " + r.lineId()
                                    + " is in the future"));
                        } else {
                            toApply.add(r);
                        }
                    }
                }
            }
            if (!invalid.isEmpty()) {
                throw new ApiException(ErrorCode.INVALID_DELIVERY_CONFIRMATION, "Invalid delivery confirmation",
                        invalid, null, Map.of());
            }
            if (conflict != null) {
                throw conflict;
            }
            if (!toApply.isEmpty() && !CONFIRMABLE.contains(order.status())) {
                throw new ApiException(ErrorCode.INVALID_ORDER_TRANSITION,
                        "Delivery cannot be confirmed while the order is " + order.status());
            }

            Set<Long> touchedItems = new LinkedHashSet<>();
            for (Requested r : toApply) {
                LineRow line = byId.get(r.lineId());
                if (!orders.markDelivered(r.lineId(), r.reference(), admin.userId(), r.deliveredAt())) {
                    throw new ApiException(ErrorCode.DELIVERY_REQUIRES_DISPATCH,
                            "Line " + r.lineId() + " changed state concurrently");
                }
                touchedItems.add(line.orderItemId());
                meters.counter("order.fulfillment.delivered").increment();
            }
            touchedItems.forEach(orders::refreshItemStatus);
            if (!toApply.isEmpty()) {
                convergence.tryComplete(orderId);
            }
            return response(orderId, requested);
        });
    }

    private record Requested(long lineId, Instant deliveredAt, String reference) {
    }

    private List<Requested> parse(JsonNode lines, Map<Long, LineRow> byId) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        List<Requested> requested = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (JsonNode item : lines) {
            Long lineId = Ids.parseCanonical(item.path("order_line_id").asText(null));
            if (lineId == null || !byId.containsKey(lineId)) {
                errors.add(new ApiException.FieldError("lines", "order_line_id does not belong to the order"));
            } else if (!seen.add(lineId)) {
                errors.add(new ApiException.FieldError("lines", "order_line_id " + lineId + " is duplicated"));
            } else {
                requested.add(new Requested(lineId, Instant.parse(item.path("delivered_at").asText()).truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                        item.path("delivery_reference").asText()));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_DELIVERY_CONFIRMATION, "Invalid delivery confirmation", errors,
                    null, Map.of());
        }
        return requested;
    }

    private ObjectNode response(long orderId, List<Requested> requested) {
        OrderRow order = orders.find(orderId).orElseThrow();
        Map<Long, LineRow> byId = new HashMap<>();
        orders.lines(orderId).forEach(l -> byId.put(l.id(), l));
        ObjectNode body = json.object();
        body.put("order_id", Ids.wire(orderId));
        body.put("order_status", order.status().name());
        body.put("order_completed", order.status() == OrderStatus.COMPLETED);
        ArrayNode lines = body.putArray("lines");
        for (Requested r : requested) {
            LineRow line = byId.get(r.lineId());
            ObjectNode node = lines.addObject();
            node.put("order_line_id", Ids.wire(line.id()));
            node.put("fulfillment_status", line.fulfillmentStatus().name());
            node.put("delivered_at", line.deliveredAt().toString());
            node.put("delivery_reference", line.deliveryReference());
        }
        return body;
    }
}
