package com.ecommerce.order.service;

import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.domain.CancelReason;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.OrderStore.LineRow;
import com.ecommerce.order.store.OrderStore.OrderRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Builds the five ORDER_* template_data payloads deterministically from the Order row and
 * its immutable snapshot plus config (interfaces.md §5.1). No runtime enrichment call is
 * made; no email, phone or detailed address leaves Order.
 */
@Component
public class NotificationComposer {

    private final OrderStore orders;
    private final OutboxWriter outbox;
    private final Json json;
    private final OrderProperties.Notification config;

    public NotificationComposer(OrderStore orders, OutboxWriter outbox, Json json, OrderProperties properties) {
        this.orders = orders;
        this.outbox = outbox;
        this.json = json;
        this.config = properties.notification();
    }

    public void orderConfirmed(OrderRow order, Instant at) {
        ObjectNode data = base(order);
        data.put("placed_at", order.createdAt().toString());
        ArrayNode items = data.putArray("items");
        List<LineRow> lines = orders.lines(order.id());
        for (LineRow line : lines) {
            ObjectNode item = items.addObject();
            item.put("name", line.snapshotName());
            item.put("variant_name", line.snapshotVariantName());
            item.put("quantity", line.quantity());
            item.put("unit_price_minor", line.unitPriceMinor());
        }
        data.put("subtotal_minor", order.totalAmountMinor());
        data.put("discount_minor", order.totalDiscountMinor());
        data.put("shipping_fee_minor", 0);
        data.put("total_minor", order.finalAmountMinor());
        data.put("currency", "VND");
        data.put("payment_method_label", config.paymentMethodLabel());
        JsonNode address = json.read(orders.items(order.id()).get(0).deliveryAddressJson());
        ObjectNode shipping = data.putObject("shipping_address");
        shipping.put("recipient_name", address.path("recipient_name").asText());
        shipping.put("ward", nameOrCode(address, "ward"));
        shipping.put("district", nameOrCode(address, "district"));
        shipping.put("city", nameOrCode(address, "province"));
        data.put("order_url", orderUrl(order.id()));
        outbox.requestNotification(order.id(), order.userId(), "ORDER_CONFIRMED", "order_confirmed:" + order.id(),
                data, at);
    }

    public void paymentFailed(OrderRow order, int attempt, boolean canRetry, Instant retryDeadline, Instant at) {
        ObjectNode data = base(order);
        data.put("amount_minor", order.finalAmountMinor());
        data.put("currency", "VND");
        data.put("attempt", attempt);
        data.put("can_retry", canRetry);
        if (canRetry) {
            data.put("retry_deadline", retryDeadline.toString());
        }
        data.put("order_url", orderUrl(order.id()));
        outbox.requestNotification(order.id(), order.userId(), "ORDER_PAYMENT_FAILED",
                "order_payment_failed:" + order.id() + ":" + attempt, data, at);
    }

    public void orderCancelled(OrderRow order, CancelReason reason, Instant cancelledAt) {
        ObjectNode data = base(order);
        data.put("cancelled_at", cancelledAt.toString());
        data.put("reason", reason.notificationDisplayReason(order.cancelReasonDetail()));
        data.put("refund_expected", false);
        data.put("order_url", orderUrl(order.id()));
        outbox.requestNotification(order.id(), order.userId(), "ORDER_CANCELLED", "order_cancelled:" + order.id(),
                data, cancelledAt);
    }

    public void orderCompleted(OrderRow order, Instant completedAt) {
        ObjectNode data = base(order);
        data.put("completed_at", completedAt.toString());
        ArrayNode items = data.putArray("items");
        for (LineRow line : orders.lines(order.id())) {
            ObjectNode item = items.addObject();
            item.put("name", line.snapshotName());
            item.put("variant_name", line.snapshotVariantName());
            item.put("quantity", line.quantity());
        }
        data.put("review_url", orderUrl(order.id()) + "?review=1");
        outbox.requestNotification(order.id(), order.userId(), "ORDER_COMPLETED", "order_completed:" + order.id(),
                data, completedAt);
    }

    public void orderRefunded(OrderRow order, String refundTransactionId, long amountMinor, Instant refundedAt) {
        ObjectNode data = base(order);
        data.put("refund_id", refundTransactionId);
        data.put("amount_minor", amountMinor);
        data.put("currency", "VND");
        data.put("refunded_at", refundedAt.toString());
        data.put("order_url", orderUrl(order.id()));
        outbox.requestNotification(order.id(), order.userId(), "ORDER_REFUNDED",
                "order_refunded:" + order.id() + ":" + refundTransactionId, data, refundedAt);
    }

    private ObjectNode base(OrderRow order) {
        ObjectNode data = json.object();
        data.put("order_id", Long.toString(order.id()));
        data.put("order_code", order.orderCode());
        return data;
    }

    private String orderUrl(long orderId) {
        String base = config.frontendBaseUrl().endsWith("/")
                ? config.frontendBaseUrl().substring(0, config.frontendBaseUrl().length() - 1)
                : config.frontendBaseUrl();
        return base + "/orders/" + orderId;
    }

    private static String nameOrCode(JsonNode address, String level) {
        JsonNode name = address.path(level + "_name");
        if (name.isTextual() && !name.asText().isBlank()) {
            return name.asText();
        }
        return address.path(level + "_code").asText();
    }
}
