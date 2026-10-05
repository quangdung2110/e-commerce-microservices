package com.ecommerce.order.service;

import com.ecommerce.order.common.ApiException;
import com.ecommerce.order.common.Ids;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.domain.BuyerTab;
import com.ecommerce.order.domain.FulfillmentStatus;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.store.OperationStore;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.OrderStore.ItemRow;
import com.ecommerce.order.store.OrderStore.LineRow;
import com.ecommerce.order.store.OrderStore.OrderRow;
import com.ecommerce.order.store.PaymentAttemptStore;
import com.ecommerce.order.store.RefundStore;
import com.ecommerce.order.store.RefundStore.CaseRow;
import com.ecommerce.order.web.Caller;
import com.ecommerce.order.workflow.WorkflowNames;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** WF-ORDER-001 detail, refund-case, payment-attempt and operation reads from PostgreSQL. */
@Service
public class OrderQueryService {

    private final OrderStore orders;
    private final RefundStore refunds;
    private final PaymentAttemptStore attempts;
    private final OperationStore operations;
    private final CheckoutSaga checkout;
    private final PaymentClientDataReader clientData;
    private final Json json;
    private final Clock clock;

    public OrderQueryService(OrderStore orders, RefundStore refunds, PaymentAttemptStore attempts,
                             OperationStore operations, CheckoutSaga checkout, PaymentClientDataReader clientData,
                             Json json, Clock clock) {
        this.orders = orders;
        this.refunds = refunds;
        this.attempts = attempts;
        this.operations = operations;
        this.checkout = checkout;
        this.clientData = clientData;
        this.json = json;
        this.clock = clock;
    }

    /** Buyer non-owner and missing order are both 404. */
    public OrderRow visible(String orderIdValue, Caller caller) {
        Long orderId = Ids.parseCanonical(orderIdValue);
        OrderRow order = orderId == null ? null : orders.find(orderId).orElse(null);
        if (order == null || (!caller.admin() && !order.userId().equals(caller.userId()))) {
            throw ApiException.notFound();
        }
        return order;
    }

    public ObjectNode detail(String orderIdValue, Caller caller) {
        OrderRow order = visible(orderIdValue, caller);
        List<LineRow> lines = orders.lines(order.id());
        boolean moved = lines.stream().anyMatch(l -> l.fulfillmentStatus() != FulfillmentStatus.PROCESSING);
        BuyerTab tab = BuyerTab.derive(order.status(), moved);
        ObjectNode body = json.object();
        body.put("order_id", Ids.wire(order.id()));
        body.put("buyer_id", order.userId().toString());
        body.put("order_status", order.status().name());
        body.put("payment_status", order.paymentStatus().name());
        body.put("buyer_tab", tab.name());
        money(body.putObject("total_amount"), order.totalAmountMinor());
        money(body.putObject("total_discount"), order.totalDiscountMinor());
        money(body.putObject("final_amount"), order.finalAmountMinor());
        body.put("cancel_reason", order.cancelReason());
        body.put("return_reason", order.returnReason());
        ArrayNode items = body.putArray("items");
        for (ItemRow item : orders.items(order.id())) {
            ObjectNode node = items.addObject();
            node.put("order_item_id", Ids.wire(item.id()));
            node.put("warehouse_id", item.warehouseId());
            node.put("status", item.status());
            node.set("delivery_address", json.read(item.deliveryAddressJson()));
            ArrayNode lineArray = node.putArray("lines");
            lines.stream().filter(l -> l.orderItemId() == item.id()).forEach(line -> line(lineArray.addObject(), line));
        }
        ArrayNode promotions = body.putArray("promotions");
        orders.promotions(order.id()).forEach(p -> {
            ObjectNode promo = promotions.addObject();
            promo.put("promotion_id", p.promotionId());
            money(promo.putObject("discount_amount"), p.discountAmountMinor());
        });
        ObjectNode payment = body.putObject("payment");
        payment.put("status", order.paymentStatus().name());
        payment.put("payment_deadline_at", order.paymentDeadlineAt().toString());
        if (order.activeAttemptNumber() == null) {
            payment.putNull("active_attempt_number");
        } else {
            payment.put("active_attempt_number", order.activeAttemptNumber());
        }
        payment.put("active_payment_transaction_id", order.activeTransactionId());
        payment.put("captured_payment_transaction_id", order.capturedTransactionId());
        CaseRow refundCase = refunds.find(order.id()).orElse(null);
        if (refundCase == null) {
            body.putNull("refund_case");
        } else {
            ObjectNode summary = body.putObject("refund_case");
            summary.put("refund_request_id", refundCase.refundRequestId());
            summary.put("mode", refundCase.mode().name());
            summary.put("status", refundCase.status().name());
            money(summary.putObject("amount"), refundCase.amountLockedMinor());
            summary.set("scope_finalization", finalization(refundCase));
            summary.put("refund_transaction_id", refundCase.refundTransactionId());
        }
        ObjectNode actions = body.putObject("available_actions");
        actions.put("can_cancel", order.status() == OrderStatus.PENDING_PAYMENT);
        actions.put("can_return", !caller.admin() && order.status() == OrderStatus.COMPLETED
                && order.buyerReturnLockedAt() == null && refundCase == null
                && lines.stream().allMatch(l -> l.fulfillmentStatus() == FulfillmentStatus.DELIVERED));
        actions.put("can_retry_payment", checkout.nextAttemptBlocker(order, clock.instant()) == null);
        body.put("created_at", order.createdAt().toString());
        body.put("updated_at", order.updatedAt().toString());
        body.put("paid_at", ts(order.paidAt()));
        body.put("completed_at", ts(order.completedAt()));
        body.put("cancelled_at", ts(order.cancelledAt()));
        body.put("refunded_at", ts(order.refundedAt()));
        return body;
    }

    public ObjectNode refundCase(String orderIdValue, Caller caller) {
        OrderRow order = visible(orderIdValue, caller);
        CaseRow refundCase = refunds.find(order.id()).orElseThrow(ApiException::notFound);
        ObjectNode body = json.object();
        body.put("order_id", Ids.wire(order.id()));
        body.put("refund_request_id", refundCase.refundRequestId());
        body.put("trigger_source", refundCase.triggerSource());
        body.put("mode", refundCase.mode().name());
        body.put("status", refundCase.status().name());
        body.set("scope_finalization", finalization(refundCase));
        money(body.putObject("amount"), refundCase.amountLockedMinor());
        ArrayNode lines = body.putArray("lines");
        refunds.lines(order.id()).forEach(line -> {
            ObjectNode node = lines.addObject();
            node.put("order_line_id", Ids.wire(line.orderLineId()));
            node.put("expected_quantity", line.expectedQuantity());
            money(node.putObject("unit_price_snapshot"), line.unitPriceMinor());
            money(node.putObject("discount_share_snapshot"), line.discountShareMinor());
            money(node.putObject("net_amount_snapshot"), line.netAmountMinor());
        });
        body.put("refund_transaction_id", refundCase.refundTransactionId());
        body.put("started_at", refundCase.startedAt().toString());
        body.put("updated_at", refundCase.updatedAt().toString());
        body.put("resolved_at", ts(refundCase.resolvedAt()));
        return body;
    }

    public ObjectNode paymentAttempt(String orderIdValue, int attemptNumber, Caller caller) {
        OrderRow order = visible(orderIdValue, caller);
        PaymentAttemptStore.AttemptRow attempt = attempts.find(order.id(), attemptNumber)
                .orElseThrow(ApiException::notFound);
        String status = attempt.terminalStatus() == null ? "PENDING" : attempt.terminalStatus();
        ObjectNode body = json.object();
        body.put("order_id", Ids.wire(order.id()));
        body.put("attempt_number", attempt.attemptNumber());
        body.put("payment_transaction_id", attempt.transactionId());
        money(body.putObject("amount"), attempt.amountMinor());
        body.put("status", status);
        boolean current = "PENDING".equals(status) && order.status() == OrderStatus.PENDING_PAYMENT
                && Integer.valueOf(attempt.attemptNumber()).equals(order.activeAttemptNumber());
        if (current) {
            ObjectNode data = clientData.read(attempt.attemptNumber(), attempt.transactionId(),
                    WorkflowNames.checkout(order.checkoutToken()));
            body.set("client_secret", data.get("client_secret"));
            body.set("publishable_key", data.get("publishable_key"));
            body.set("expires_at", data.get("expires_at"));
        } else {
            body.putNull("client_secret");
            body.putNull("publishable_key");
            body.putNull("expires_at");
        }
        body.put("created_at", attempt.createdAt().toString());
        body.put("resolved_at", ts(attempt.resolvedAt()));
        return body;
    }

    public ObjectNode operation(String operationIdValue, Caller caller) {
        UUID operationId = Ids.parseUuid(operationIdValue);
        OperationStore.OperationRow op = operationId == null ? null : operations.find(operationId).orElse(null);
        if (op == null || (!caller.admin() && !(op.ownerPrincipalId().equals(caller.principal())
                && "BUYER".equals(op.ownerRole())))) {
            throw ApiException.notFound();
        }
        ObjectNode body = json.object();
        body.put("operation_id", op.operationId().toString());
        body.put("operation_type", op.type().name());
        body.put("order_id", Ids.wire(op.orderId()));
        body.put("status", op.status().name());
        body.put("result_reference", op.resultReference());
        body.set("failure", op.problemJson() == null ? json.mapper().nullNode() : json.read(op.problemJson()));
        body.put("created_at", op.createdAt().toString());
        body.put("updated_at", op.updatedAt().toString());
        body.put("completed_at", ts(op.completedAt()));
        return body;
    }

    private ObjectNode finalization(CaseRow refundCase) {
        ObjectNode node = json.object();
        node.put("status", refundCase.finalizationStatus().name());
        boolean completed = refundCase.finalizationStatus().name().equals("COMPLETED");
        node.put("terminal_scope_state", completed ? refundCase.scopeTerminalState() : null);
        node.put("finalized_at", completed ? ts(refundCase.finalizedAt()) : null);
        return node;
    }

    private void line(ObjectNode node, LineRow line) {
        node.put("order_line_id", Ids.wire(line.id()));
        node.put("product_id", line.productId());
        node.put("sku_id", line.skuId());
        node.put("quantity", line.quantity());
        money(node.putObject("unit_price"), line.unitPriceMinor());
        node.put("fulfillment_status", line.fulfillmentStatus().name());
        node.put("snapshot_name", line.snapshotName());
        node.put("snapshot_image_url", line.snapshotImageUrl());
        node.set("snapshot_attributes", json.read(line.snapshotAttributesJson()));
        node.put("delivered_at", ts(line.deliveredAt()));
    }

    private static void money(ObjectNode node, long amountMinor) {
        node.put("amount_minor", amountMinor);
        node.put("currency", "VND");
    }

    private static String ts(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
