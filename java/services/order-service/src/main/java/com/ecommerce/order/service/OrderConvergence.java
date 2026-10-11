package com.ecommerce.order.service;

import com.ecommerce.order.common.Json;
import com.ecommerce.order.domain.FulfillmentStatus;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.OrderStore.LineRow;
import com.ecommerce.order.store.OrderStore.OrderRow;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code TryCompleteOrder} (INV-ORD-006/030, ADR-015): the single convergence routine run
 * after every delivery change and after financial repair returns to PROCESSING. It
 * completes the master only when it is PROCESSING, every line is DELIVERED by Admin
 * confirmation (ADR-021) and no post-capture repair obligation is open. Callers hold the order row lock.
 */
@Component
public class OrderConvergence {

    private final OrderStore orders;
    private final ReconciliationLedger reconciliation;
    private final OutboxWriter outbox;
    private final NotificationComposer notifications;
    private final Json json;
    private final Clock clock;
    private final MeterRegistry meters;

    public OrderConvergence(OrderStore orders, ReconciliationLedger reconciliation, OutboxWriter outbox,
                            NotificationComposer notifications, Json json, Clock clock, MeterRegistry meters) {
        this.orders = orders;
        this.reconciliation = reconciliation;
        this.outbox = outbox;
        this.notifications = notifications;
        this.json = json;
        this.clock = clock;
        this.meters = meters;
    }

    public boolean tryComplete(long orderId) {
        OrderRow order = orders.lock(orderId).orElseThrow();
        if (order.status() != OrderStatus.PROCESSING) {
            return false;
        }
        List<LineRow> lines = orders.lockLines(orderId);
        if (lines.isEmpty() || lines.stream().anyMatch(l -> l.fulfillmentStatus() != FulfillmentStatus.DELIVERED)) {
            return false;
        }
        if (reconciliation.hasOpenPostCaptureRepair(orderId)) {
            return false;
        }
        Instant now = clock.instant();
        orders.update(orderId, Map.of("status", OrderStatus.COMPLETED, "completed_at", now, "saga_phase", "CLOSED"));
        ObjectNode data = json.object();
        data.put("order_id", Long.toString(orderId));
        data.put("user_id", order.userId().toString());
        data.put("completed_at", now.toString());
        ArrayNode items = data.putArray("items");
        Map<String, ObjectNode> byProductSku = new LinkedHashMap<>();
        for (LineRow line : lines) {
            String key = line.productId() + "\u0000" + line.skuId();
            ObjectNode existing = byProductSku.get(key);
            if (existing == null) {
                ObjectNode item = items.addObject();
                item.put("product_id", line.productId());
                item.put("sku_id", line.skuId());
                item.put("variant_name", line.snapshotVariantName());
                item.put("product_name", line.snapshotName());
                item.put("product_image_url", line.snapshotImageUrl());
                item.put("quantity", line.quantity());
                byProductSku.put(key, item);
            } else {
                existing.put("quantity", existing.get("quantity").asInt() + line.quantity());
            }
        }
        outbox.appendFact(orderId, OutboxWriter.ORDER_COMPLETED, data, now);
        notifications.orderCompleted(orders.find(orderId).orElseThrow(), now);
        meters.counter("order.lifecycle", "transition", "COMPLETED").increment();
        return true;
    }
}
