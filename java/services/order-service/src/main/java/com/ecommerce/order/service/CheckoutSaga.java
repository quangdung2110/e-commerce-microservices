package com.ecommerce.order.service;

import com.ecommerce.order.client.Gateways;
import com.ecommerce.order.client.RemoteResult;
import com.ecommerce.order.common.Ids;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.domain.CancelReason;
import com.ecommerce.order.domain.DiscountAllocator;
import com.ecommerce.order.domain.NameNormalizer;
import com.ecommerce.order.domain.VariantSnapshot;
import com.ecommerce.order.domain.OperationTypes;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.domain.PaymentStatus;
import com.ecommerce.order.domain.RefundTypes.ObligationType;
import com.ecommerce.order.store.CheckpointStore;
import com.ecommerce.order.store.CheckpointStore.CheckpointRow;
import com.ecommerce.order.store.OperationStore;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.OrderStore.LineRow;
import com.ecommerce.order.store.OrderStore.OrderRow;
import com.ecommerce.order.store.OrderStore.PromotionRow;
import com.ecommerce.order.store.PaymentAttemptStore;
import com.ecommerce.order.store.PaymentAttemptStore.AttemptRow;
import com.ecommerce.order.store.SubmissionStore;
import com.ecommerce.order.web.dto.CheckoutDtos.AllocationPlanLine;
import com.ecommerce.order.web.dto.CheckoutDtos.CheckoutItem;
import com.ecommerce.order.web.dto.CheckoutDtos.CheckoutOrderSubmissionRequest;
import com.ecommerce.order.web.dto.CheckoutDtos.PromotionSnapshot;
import com.ecommerce.order.workflow.ActivityResults.Applied;
import com.ecommerce.order.workflow.ActivityResults.CancelResult;
import com.ecommerce.order.workflow.ActivityResults.NextAttempt;
import com.ecommerce.order.workflow.ActivityResults.Outcome;
import com.ecommerce.order.workflow.ActivityResults.Prepared;
import com.ecommerce.order.workflow.ActivityResults.Ready;
import com.ecommerce.order.workflow.WorkflowNames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Activities of OrderCheckoutWorkflow (WF-ORDER-004/005/009/011). Each method is
 * idempotent: local writes run under the order row lock and remote commands go through
 * {@link RemoteCommands} checkpoints. Pre-payment failures compensate; after the Payment
 * financial watermark only forward repair or reconciliation is possible (ADR-011/014).
 */
@Service
public class CheckoutSaga {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSaga.class);

    static final String LANE = "CHECKOUT";

    private final OrderStore orders;
    private final SubmissionStore submissions;
    private final PaymentAttemptStore attempts;
    private final OperationStore operations;
    private final CheckpointStore checkpoints;
    private final RemoteCommands remote;
    private final Gateways.Inventory inventory;
    private final Gateways.Promotion promotion;
    private final Gateways.Payment payment;
    private final OutboxWriter outbox;
    private final NotificationComposer notifications;
    private final ReconciliationLedger reconciliation;
    private final OrderConvergence convergence;
    private final ProblemFactory problems;
    private final InboxReplayer replayer;
    private final TransactionTemplate tx;
    private final Json json;
    private final Clock clock;
    private final OrderProperties.Checkout config;
    private final MeterRegistry meters;

    public CheckoutSaga(OrderStore orders, SubmissionStore submissions, PaymentAttemptStore attempts,
                        OperationStore operations, CheckpointStore checkpoints, RemoteCommands remote,
                        Gateways.Inventory inventory, Gateways.Promotion promotion, Gateways.Payment payment,
                        OutboxWriter outbox, NotificationComposer notifications, ReconciliationLedger reconciliation,
                        OrderConvergence convergence, ProblemFactory problems, InboxReplayer replayer,
                        TransactionTemplate tx, Json json, Clock clock, OrderProperties properties,
                        MeterRegistry meters) {
        this.orders = orders;
        this.submissions = submissions;
        this.attempts = attempts;
        this.operations = operations;
        this.checkpoints = checkpoints;
        this.remote = remote;
        this.inventory = inventory;
        this.promotion = promotion;
        this.payment = payment;
        this.outbox = outbox;
        this.notifications = notifications;
        this.reconciliation = reconciliation;
        this.convergence = convergence;
        this.problems = problems;
        this.replayer = replayer;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.config = properties.checkout();
        this.meters = meters;
    }

    // ------------------------------------------------------------------ CreateOrderActivity

    /**
     * Creates master/items/lines/promotions/bridge from the persisted submission exactly
     * once (T1); a replay returns the existing order. No outbox row is written.
     */
    public Prepared prepareOrder(String checkoutToken) {
        return tx.execute(s -> {
            UUID token = UUID.fromString(checkoutToken);
            SubmissionStore.SubmissionRow submission = submissions.lock(token).orElseThrow(
                    () -> new IllegalStateException("No submission for checkout token " + checkoutToken));
            long orderId = submission.orderId() != null ? submission.orderId() : createOrder(submission);
            OrderRow order = orders.find(orderId).orElseThrow();
            List<String> promotionIds = orders.promotions(orderId).stream().map(PromotionRow::promotionId).toList();
            return new Prepared(orderId, order.status().name(), promotionIds, order.paymentDeadlineLockedAt() != null,
                    order.paymentDeadlineAt().toEpochMilli());
        });
    }

    private long createOrder(SubmissionStore.SubmissionRow submission) {
        CheckoutOrderSubmissionRequest request = json.read(submission.requestJson(), CheckoutOrderSubmissionRequest.class);
        JsonNode raw = json.read(submission.requestJson());
        Instant now = clock.instant();
        long orderId = orders.nextOrderId();
        orders.insert(new OrderStore.NewOrder(orderId, UUID.fromString(request.userId()), request.checkoutToken(),
                request.pricingHash(), request.itemsHash(), request.promotionCampaignRevision(),
                request.pricing().totalAmount().amountMinor(), request.pricing().totalDiscount().amountMinor(),
                request.pricing().finalAmount().amountMinor(), request.allocationPlan().allocationPlanId(),
                now.plus(config.paymentWindow()), now));
        String address = json.write(raw.get("delivery_address_snapshot"));
        Map<String, CheckoutItem> itemsByReference = new HashMap<>();
        request.items().forEach(item -> itemsByReference.put(item.lineReference(), item));
        Map<String, Long> deliveryUnits = new LinkedHashMap<>();
        List<DiscountAllocator.Line> grossLines = new ArrayList<>();
        for (AllocationPlanLine planLine : request.allocationPlan().lines()) {
            long itemId = deliveryUnits.computeIfAbsent(planLine.warehouseId(),
                    warehouse -> orders.insertItem(orderId, warehouse, address));
            CheckoutItem item = itemsByReference.get(planLine.lineReference());
            long lineId = orders.insertLine(orderId, new OrderStore.NewLine(itemId,
                    request.allocationPlan().allocationPlanId(), planLine.lineReference(), planLine.warehouseId(),
                    item.skuId(), item.productId(), item.quantity(), item.unitPrice().amountMinor(), 0,
                    item.snapshotName(), VariantSnapshot.name(item.variantName(), item.snapshotAttributes()), item.snapshotImageUrl(),
                    NameNormalizer.normalize(item.snapshotName()), json.write(item.snapshotAttributes()),
                    json.write(item.categoryIds())));
            grossLines.add(new DiscountAllocator.Line(lineId, item.unitPrice().amountMinor() * item.quantity()));
        }
        DiscountAllocator.allocate(grossLines, request.pricing().totalDiscount().amountMinor())
                .forEach(orders::setDiscountShare);
        for (PromotionSnapshot p : request.promotions()) {
            orders.insertPromotion(orderId, p.promotionId(), p.discountAmount().amountMinor());
        }
        submissions.attachOrder(submission.checkoutToken(), orderId);
        meters.counter("order.lifecycle", "transition", "CREATED").increment();
        return orderId;
    }

    // ------------------------------------------------------------------ Reserve (pre-payment)

    public Outcome reserveInventory(long orderId) {
        OrderRow order = orders.find(orderId).orElseThrow();
        ObjectNode body = json.object();
        body.put("order_id", Ids.wire(orderId));
        body.put("allocation_plan_id", order.allocationPlanId());
        ArrayNode lines = body.putArray("lines");
        for (LineRow line : orders.lines(orderId)) {
            lines.addObject().put("order_line_id", Ids.wire(line.id())).put("sku_id", line.skuId())
                    .put("quantity", line.quantity()).put("warehouse_id", line.warehouseId());
        }
        RemoteResult result = remote.execute(reserveKey(orderId), "order-" + orderId + "-inventory-reserve", body,
                cp -> inventory.reserve(command(order, cp), json.read(cp.requestJson())),
                r -> "REJECT_ALL".equals(r.body().path("outcome").asText())
                        ? RemoteResult.rejected(r.httpStatus(), "REJECT_ALL", r.body()) : r,
                r -> {
                    if (r.isAccepted()) {
                        orders.lock(orderId);
                        orders.update(orderId, Map.of("inventory_reservation_expires_at",
                                Instant.parse(r.body().path("expires_at").asText())));
                    }
                });
        return outcome(result);
    }

    public Outcome reservePromotion(long orderId, String promotionId) {
        OrderRow order = orders.find(orderId).orElseThrow();
        PromotionRow promo = orders.promotions(orderId).stream().filter(p -> p.promotionId().equals(promotionId))
                .findFirst().orElseThrow();
        ObjectNode body = json.object();
        body.put("order_id", Ids.wire(orderId));
        body.put("user_id", order.userId().toString());
        body.put("promotion_id", promotionId);
        money(body.putObject("expected_discount"), promo.discountAmountMinor());
        ObjectNode context = body.putObject("context");
        money(context.putObject("subtotal"), order.totalAmountMinor());
        context.put("payment_method", "STRIPE");
        context.put("checkout_channel", "CHECKOUT");
        ArrayNode items = context.putArray("items");
        for (LineRow line : orders.lines(orderId)) {
            ObjectNode item = items.addObject();
            item.put("product_id", line.productId());
            item.put("sku_id", line.skuId());
            item.put("quantity", line.quantity());
            money(item.putObject("unit_price"), line.unitPriceMinor());
            item.set("category_ids", json.read(line.snapshotCategoryIdsJson()));
        }
        RemoteResult result = remote.execute(
                new CheckpointStore.Key(orderId, LANE, "PRE_PAYMENT", "PROMOTION_RESERVE", promotionId, 0),
                "order-" + orderId + "-promotion-reserve-" + promotionId, body,
                cp -> promotion.reserve(json.read(cp.requestJson())),
                r -> {
                    long discount = r.body().path("discount").path("amount_minor").asLong(-1);
                    return discount == promo.discountAmountMinor() ? r
                            : RemoteResult.rejected(r.httpStatus(), "EXPECTED_DISCOUNT_MISMATCH", r.body());
                },
                r -> {
                    if (r.isAccepted()) {
                        orders.recordPromotionReservation(orderId, promotionId,
                                Instant.parse(r.body().path("reserved_at").asText()),
                                Instant.parse(r.body().path("expires_at").asText()));
                    }
                });
        return outcome(result);
    }

    // ------------------------------------------------------------------ Payment initiation

    public Outcome initiatePayment(long orderId, int attemptNumber, String operationId, String returnUrl) {
        OrderRow order = orders.find(orderId).orElseThrow();
        ObjectNode body = json.object();
        body.put("order_id", Ids.wire(orderId));
        body.put("payment_attempt_number", attemptNumber);
        money(body.putObject("amount"), order.finalAmountMinor());
        body.put("user_id", order.userId().toString());
        if (returnUrl != null) {
            body.put("return_url", returnUrl);
        }
        UUID operation = operationId == null ? null : UUID.fromString(operationId);
        RemoteResult result = remote.execute(
                new CheckpointStore.Key(orderId, LANE, "PRE_PAYMENT", "PAYMENT_INITIATE", Integer.toString(attemptNumber), 0),
                "order-" + orderId + "-payment-attempt-" + attemptNumber, body,
                cp -> payment.initiate(cp.commandId(), WorkflowNames.checkout(order.checkoutToken()),
                        json.read(cp.requestJson())),
                r -> "INITIATING".equals(r.body().path("status").asText())
                        ? new RemoteResult(RemoteResult.Kind.UNKNOWN, r.httpStatus(), "INITIATING", r.body()) : r,
                r -> {
                    if (r.isAccepted()) {
                        bindAttempt(orderId, attemptNumber, r.body(), order.finalAmountMinor(), operation);
                    }
                });
        if (result.isAccepted()) {
            // A Payment fact that arrived before this binding was parked; replay it now.
            replayer.replayIdentityBind(orderId);
        }
        return outcome(result);
    }

    private void bindAttempt(long orderId, int attemptNumber, JsonNode body, long amount, UUID operation) {
        OrderRow order = orders.lock(orderId).orElseThrow();
        String txn = body.path("transaction_id").asText();
        attempts.bind(orderId, attemptNumber, txn, amount, operation);
        Integer active = order.activeAttemptNumber();
        if (active == null || active < attemptNumber) {
            Map<String, Object> changes = new HashMap<>();
            changes.put("active_payment_attempt_number", attemptNumber);
            changes.put("active_payment_transaction_id", txn);
            if (order.status() == OrderStatus.PENDING_PAYMENT) {
                changes.put("payment_status", PaymentStatus.PENDING);
            }
            orders.update(orderId, changes);
        }
    }

    /** Locks the immutable payment deadline once (ADR-014, ADR-020 §12). */
    public Ready markPaymentReady(long orderId) {
        return tx.execute(s -> {
            OrderRow order = orders.lock(orderId).orElseThrow();
            if (order.status() != OrderStatus.PENDING_PAYMENT) {
                return new Ready(order.status() == OrderStatus.CANCELLING ? "CANCELLING" : order.status().name(),
                        order.paymentDeadlineAt().toEpochMilli());
            }
            if (order.paymentDeadlineLockedAt() != null) {
                return new Ready("READY", order.paymentDeadlineAt().toEpochMilli());
            }
            Instant now = clock.instant();
            Instant deadline = order.paymentDeadlineAt();
            deadline = min(deadline, now.plus(config.paymentWindow()));
            if (order.inventoryReservationExpiresAt() != null) {
                deadline = min(deadline, order.inventoryReservationExpiresAt().minus(config.clockSkewSafety()));
            }
            for (PromotionRow p : orders.promotions(orderId)) {
                if (p.reservationExpiresAt() != null) {
                    deadline = min(deadline, p.reservationExpiresAt().minus(config.clockSkewSafety()));
                }
            }
            if (!deadline.isAfter(now)) {
                return new Ready("WINDOW_EXHAUSTED", deadline.toEpochMilli());
            }
            orders.update(orderId, Map.of("payment_deadline_at", deadline, "payment_deadline_locked_at", now,
                    "saga_phase", "AWAITING_PAYMENT"));
            submissions.transition(UUID.fromString(order.checkoutToken()), "PAYMENT_READY", null);
            return new Ready("READY", deadline.toEpochMilli());
        });
    }

    /**
     * Pre-payment failure before PAYMENT_READY: terminal submission state for Checkout and
     * T4.2 into CANCELLING with PRE_PAYMENT_SAGA_FAILED (unless the Buyer already cancelled).
     */
    public void failPreparation(long orderId, String errorCode, String failedPromotionId, String failedReason) {
        tx.executeWithoutResult(s -> {
            OrderRow order = orders.lock(orderId).orElseThrow();
            boolean recalculation = "PROMOTION_RECALCULATION_NEEDED".equals(errorCode);
            ObjectNode problem = recalculation
                    ? problems.promotionRecalculation(failedPromotionId, failedReason)
                    : problems.submissionFailure(errorCode);
            submissions.transition(UUID.fromString(order.checkoutToken()),
                    recalculation ? "RECALCULATION_REQUIRED" : "FAILED", json.write(problem));
            if (order.status() == OrderStatus.PENDING_PAYMENT) {
                String detail = "INVENTORY_RESERVATION_REJECTED".equals(errorCode)
                        ? CancelReason.DETAIL_INVENTORY_REJECTED : errorCode;
                orders.update(orderId, Map.of("status", OrderStatus.CANCELLING,
                        "cancel_reason", CancelReason.PRE_PAYMENT_SAGA_FAILED.name(), "cancel_reason_detail", detail,
                        "saga_phase", "CANCELLING"));
            }
        });
    }

    // ------------------------------------------------------------------ Payment facts

    /** Applies a validated PaymentResult message (consumer already checked identity/version). */
    public Applied applyPaymentResult(long orderId, Map<String, String> attrs) {
        String type = attrs.get("result");
        String txn = attrs.get("payment_transaction_id");
        return switch (type) {
            case "SUCCESS" -> markPaid(orderId, txn, Long.parseLong(attrs.get("amount_minor")));
            case "FAILED" -> recordFailedAttempt(orderId, txn, attrs.get("error_code"));
            case "EXPIRED", "CANCELED" -> recordNonCapture(orderId, txn, type, attrs.get("reason_code"));
            default -> new Applied(Applied.IGNORED);
        };
    }

    /**
     * T2/T3.2: freezes the first matching capture and emits ORDER_PAID + ORDER_CONFIRMED in
     * the same transaction. Capture after a terminal non-capture, a second capture or an
     * amount mismatch opens CAPTURE_AFTER_TERMINAL_NON_CAPTURE and never revives the order.
     */
    public Applied markPaid(long orderId, String txn, long amountMinor) {
        return tx.execute(s -> {
            OrderRow order = orders.lock(orderId).orElseThrow();
            Optional<AttemptRow> attempt = attempts.findByTransaction(txn).filter(a -> a.orderId() == orderId);
            if (order.capturedTransactionId() != null) {
                if (order.capturedTransactionId().equals(txn)) {
                    return new Applied(Applied.ALREADY_PAID);
                }
                anomaly(orderId, txn, "SECOND_CAPTURE");
                return new Applied(Applied.ANOMALY);
            }
            boolean terminalNonCapture = attempt.map(a -> a.terminalStatus() != null
                    && !"SUCCESS".equals(a.terminalStatus())).orElse(true);
            if (attempt.isEmpty() || attempt.get().amountMinor() != amountMinor || terminalNonCapture
                    || !order.status().isPrePayment()) {
                anomaly(orderId, txn, attempt.isEmpty() ? "UNKNOWN_TRANSACTION"
                        : attempt.get().amountMinor() != amountMinor ? "AMOUNT_MISMATCH" : "CAPTURE_AFTER_TERMINAL");
                return new Applied(Applied.ANOMALY);
            }
            Instant now = clock.instant();
            attempts.markTerminal(txn, "SUCCESS", null, now);
            Map<String, Object> changes = new HashMap<>();
            changes.put("status", OrderStatus.PAID);
            changes.put("payment_status", PaymentStatus.PAID);
            changes.put("captured_payment_transaction_id", txn);
            changes.put("active_payment_transaction_id", txn);
            changes.put("active_payment_attempt_number", attempt.get().attemptNumber());
            changes.put("paid_at", now);
            changes.put("saga_phase", "POST_CAPTURE_REPAIR");
            changes.put("cancel_reason", null);
            changes.put("cancel_reason_detail", null);
            orders.update(orderId, changes);
            ObjectNode data = json.object();
            data.put("order_id", Ids.wire(orderId));
            data.put("user_id", order.userId().toString());
            ArrayNode skus = data.putArray("sku_ids");
            Set<String> unique = new LinkedHashSet<>();
            orders.lines(orderId).forEach(l -> unique.add(l.skuId()));
            unique.forEach(skus::add);
            outbox.appendFact(orderId, OutboxWriter.ORDER_PAID, data, now);
            notifications.orderConfirmed(orders.find(orderId).orElseThrow(), now);
            rejectOpen(orderId, OperationTypes.Type.BUYER_CANCELLATION, "Payment was captured before cancellation");
            rejectOpen(orderId, OperationTypes.Type.ADMIN_CANCELLATION, "Payment was captured before cancellation");
            rejectOpen(orderId, OperationTypes.Type.PAYMENT_ATTEMPT, "Payment was already captured");
            meters.counter("order.lifecycle", "transition", "PAID").increment();
            return new Applied(Applied.PAID);
        });
    }

    private Applied recordFailedAttempt(long orderId, String txn, String errorCode) {
        return tx.execute(s -> {
            OrderRow order = orders.lock(orderId).orElseThrow();
            Instant now = clock.instant();
            boolean marked = attempts.markTerminal(txn, "FAILED", errorCode, now);
            AttemptRow attempt = attempts.findByTransaction(txn).orElseThrow();
            boolean active = txn.equals(order.activeTransactionId());
            if (!active || order.status() != OrderStatus.PENDING_PAYMENT) {
                return new Applied(Applied.IGNORED);
            }
            orders.update(orderId, Map.of("payment_status", PaymentStatus.FAILED));
            if (marked) {
                OrderRow updated = orders.find(orderId).orElseThrow();
                boolean canRetry = holdsValid(updated, now) && updated.paymentDeadlineLockedAt() != null
                        && updated.paymentDeadlineAt().isAfter(now);
                notifications.paymentFailed(updated, attempt.attemptNumber(), canRetry, updated.paymentDeadlineAt(), now);
            }
            return new Applied(Applied.ATTEMPT_FAILED);
        });
    }

    private Applied recordNonCapture(long orderId, String txn, String status, String reasonCode) {
        return tx.execute(s -> {
            OrderRow order = orders.lock(orderId).orElseThrow();
            Instant now = clock.instant();
            boolean marked = attempts.markTerminal(txn, status, reasonCode, now);
            AttemptRow attempt = attempts.findByTransaction(txn).orElseThrow();
            if (!txn.equals(order.activeTransactionId())) {
                return new Applied(Applied.IGNORED);
            }
            if (marked && "EXPIRED".equals(status)) {
                notifications.paymentFailed(order, attempt.attemptNumber(), false, null, now);
            }
            if (order.status() == OrderStatus.PENDING_PAYMENT) {
                orders.update(orderId, Map.of("status", OrderStatus.CANCELLING, "payment_status", PaymentStatus.FAILED,
                        "cancel_reason", CancelReason.forTerminalNonCapture(status, reasonCode).name(),
                        "saga_phase", "CANCELLING"));
                return new Applied(Applied.CANCEL_STARTED);
            }
            return new Applied(order.status() == OrderStatus.CANCELLING ? Applied.NON_CAPTURE : Applied.IGNORED);
        });
    }

    // ------------------------------------------------------------------ Cancellation

    /** Deadline timer won (WF-ORDER-009): T3 with the INV-ORD-034 reason. */
    public Applied beginDeadlineCancellation(long orderId) {
        return tx.execute(s -> {
            OrderRow order = orders.lock(orderId).orElseThrow();
            if (order.status() == OrderStatus.PENDING_PAYMENT) {
                String last = order.activeAttemptNumber() == null ? null
                        : attempts.find(orderId, order.activeAttemptNumber()).map(AttemptRow::terminalStatus).orElse(null);
                orders.update(orderId, Map.of("status", OrderStatus.CANCELLING,
                        "cancel_reason", CancelReason.forDeadline(last).name(), "saga_phase", "CANCELLING"));
                return new Applied(Applied.CANCEL_STARTED);
            }
            return switch (order.status()) {
                case CANCELLING -> new Applied(Applied.CANCEL_STARTED);
                case CANCELLED -> new Applied(Applied.CANCELLED);
                default -> new Applied(Applied.ALREADY_PAID);
            };
        });
    }

    /**
     * Resolves Payment cancel versus capture before any resource release (T3/T3.1/T3.2).
     * ALREADY_SUCCEEDED applies T3.2 atomically; CANCEL_PENDING/unknown keeps waiting.
     */
    public CancelResult cancelPayment(long orderId) {
        OrderRow order = orders.find(orderId).orElseThrow();
        if (order.status() != OrderStatus.CANCELLING) {
            return new CancelResult(order.status() == OrderStatus.CANCELLED ? "NON_CAPTURE"
                    : order.capturedTransactionId() != null ? "CAPTURED" : "PENDING");
        }
        String txn = order.activeTransactionId();
        if (txn == null) {
            return new CancelResult("NO_PAYMENT");
        }
        CancelReason reason = CancelReason.valueOf(order.cancelReason());
        ObjectNode body = json.object();
        body.put("order_id", Ids.wire(orderId));
        body.put("reason", reason.paymentCancelReason());
        CheckpointStore.Key key = new CheckpointStore.Key(orderId, LANE, "CANCELLING", "PAYMENT_CANCEL", txn, 0);
        // CANCEL_PENDING is not durable: re-ask Payment with the same key until resolved.
        RemoteResult result = remote.execute(key, "order-" + orderId + "-payment-cancel-" + txn, body,
                cp -> payment.cancel(txn, cp.commandId(), WorkflowNames.checkout(order.checkoutToken()),
                        json.read(cp.requestJson())),
                r -> "CANCEL_PENDING".equals(r.body().path("outcome").asText())
                        ? new RemoteResult(RemoteResult.Kind.UNKNOWN, r.httpStatus(), "CANCEL_PENDING", r.body()) : r,
                r -> {
                    if (r.isAccepted() && !"ALREADY_SUCCEEDED".equals(r.body().path("outcome").asText())) {
                        attempts.markTerminal(txn, "CANCELED", "ORDER_CANCEL", clock.instant());
                    }
                });
        if (result.isAccepted()) {
            if ("ALREADY_SUCCEEDED".equals(result.body().path("outcome").asText())) {
                long amount = result.body().path("amount").path("amount_minor").asLong();
                Applied applied = markPaid(orderId, txn, amount);
                return new CancelResult(applied.is(Applied.ANOMALY) ? "PENDING" : "CAPTURED");
            }
            return new CancelResult("NON_CAPTURE");
        }
        if (result.isRejected()) {
            log.error("Payment rejected cancel for order {} txn {}: {}", orderId, txn, result.errorCode());
        }
        return new CancelResult("PENDING");
    }

    public Outcome releasePromotion(long orderId, String promotionId) {
        Optional<CheckpointRow> reserve = checkpoints.find(
                new CheckpointStore.Key(orderId, LANE, "PRE_PAYMENT", "PROMOTION_RESERVE", promotionId, 0));
        if (reserve.isEmpty() || reserve.get().rejected()) {
            return Outcome.accepted();
        }
        if (!reserve.get().accepted()) {
            Outcome replay = reservePromotion(orderId, promotionId);
            if (replay.isRejected()) {
                return Outcome.accepted();
            }
            if (replay.isUnknown()) {
                return replay;
            }
        }
        ObjectNode body = json.object();
        body.put("order_id", Ids.wire(orderId));
        body.put("promotion_id", promotionId);
        RemoteResult result = remote.execute(
                new CheckpointStore.Key(orderId, LANE, "CANCELLING", "PROMOTION_CANCEL", promotionId, 0),
                "order-" + orderId + "-promotion-cancel-" + promotionId, body,
                cp -> promotion.cancel(json.read(cp.requestJson())),
                r -> switch (r.body().path("outcome").asText()) {
                    case "RELEASED", "NO_RESERVATION" -> r;
                    default -> new RemoteResult(RemoteResult.Kind.UNKNOWN, r.httpStatus(),
                            r.body().path("outcome").asText("UNKNOWN"), r.body());
                },
                RemoteCommands.noEffect());
        return outcome(result);
    }

    public Outcome releaseInventory(long orderId) {
        Optional<CheckpointRow> reserve = checkpoints.find(reserveKey(orderId));
        if (reserve.isEmpty() || reserve.get().rejected()) {
            return Outcome.accepted();
        }
        if (!reserve.get().accepted()) {
            Outcome replay = reserveInventory(orderId);
            if (replay.isRejected()) {
                return Outcome.accepted();
            }
            if (replay.isUnknown()) {
                return replay;
            }
        }
        OrderRow order = orders.find(orderId).orElseThrow();
        ObjectNode body = json.object();
        body.put("reason", CancelReason.valueOf(order.cancelReason()).inventoryReleaseReason());
        RemoteResult result = remote.execute(
                new CheckpointStore.Key(orderId, LANE, "CANCELLING", "INVENTORY_RELEASE", "", 0),
                "order-" + orderId + "-inventory-release", body,
                cp -> inventory.release(Ids.wire(orderId), command(order, cp), json.read(cp.requestJson())),
                RemoteCommands.asIs(), RemoteCommands.noEffect());
        if (result.isRejected() && "RESERVATION_NOT_RESERVED".equals(result.errorCode())) {
            RemoteResult current = inventory.getReservation(Ids.wire(orderId));
            if (current.isAccepted() && "RELEASED".equals(current.body().path("state").asText())) {
                return Outcome.accepted();
            }
            log.error("Inventory release conflict for order {}: reservation state {}", orderId,
                    current.body() == null ? "?" : current.body().path("state").asText());
            return Outcome.unknown("RESERVATION_NOT_RESERVED");
        }
        return outcome(result);
    }

    /** T3.1 after Payment non-capture and both cleanup receipts are proven. */
    public void completeCancellation(long orderId) {
        tx.executeWithoutResult(s -> {
            OrderRow order = orders.lock(orderId).orElseThrow();
            if (order.status() != OrderStatus.CANCELLING) {
                return;
            }
            Instant now = clock.instant();
            orders.update(orderId, Map.of("status", OrderStatus.CANCELLED, "cancelled_at", now, "saga_phase", "CLOSED"));
            OrderRow updated = orders.find(orderId).orElseThrow();
            notifications.orderCancelled(updated, CancelReason.valueOf(updated.cancelReason()), now);
            for (OperationTypes.Type type : List.of(OperationTypes.Type.BUYER_CANCELLATION,
                    OperationTypes.Type.ADMIN_CANCELLATION)) {
                operations.openByOrder(orderId, type).forEach(op ->
                        operations.transition(op.operationId(), OperationTypes.Status.SUCCEEDED,
                                "/api/v1/orders/" + orderId, null));
            }
            rejectOpen(orderId, OperationTypes.Type.PAYMENT_ATTEMPT, "The order was cancelled");
            submissions.transition(UUID.fromString(order.checkoutToken()), "FAILED",
                    json.write(problems.submissionFailure("ORDER_CANCELLED_DURING_PREPARATION")));
            meters.counter("order.lifecycle", "transition", "CANCELLED").increment();
        });
    }

    // ------------------------------------------------------------------ Post-capture forward repair

    public Outcome commitInventory(long orderId) {
        OrderRow order = orders.find(orderId).orElseThrow();
        ObjectNode body = json.object();
        body.put("payment_reference", order.capturedTransactionId());
        RemoteResult result = remote.execute(
                new CheckpointStore.Key(orderId, LANE, "POST_CAPTURE", "INVENTORY_COMMIT", "", 0),
                "order-" + orderId + "-inventory-commit", body,
                cp -> inventory.commit(Ids.wire(orderId), command(order, cp), json.read(cp.requestJson())),
                RemoteCommands.asIs(), RemoteCommands.noEffect());
        return outcome(result);
    }

    public Outcome confirmPromotion(long orderId, String promotionId) {
        PromotionRow promo = orders.promotions(orderId).stream().filter(p -> p.promotionId().equals(promotionId))
                .findFirst().orElseThrow();
        ObjectNode body = json.object();
        body.put("order_id", Ids.wire(orderId));
        body.put("promotion_id", promotionId);
        money(body.putObject("expected_discount"), promo.discountAmountMinor());
        String key = "REDEEM_FINAL:" + orderId + ":" + promotionId;
        RemoteResult result = remote.execute(
                new CheckpointStore.Key(orderId, LANE, "POST_CAPTURE", "PROMOTION_CONFIRM", promotionId, 0), key, body,
                cp -> promotion.confirm(cp.commandId(), json.read(cp.requestJson())),
                RemoteCommands.asIs(), RemoteCommands.noEffect());
        return outcome(result);
    }

    /** T8.1: preserve the paid fact and open a typed repair obligation; never roll back. */
    public void escalatePostCapture(long orderId, String obligationType, String detail) {
        tx.executeWithoutResult(s -> {
            OrderRow order = orders.lock(orderId).orElseThrow();
            if (order.status() == OrderStatus.PAID || order.status() == OrderStatus.PROCESSING) {
                orders.update(orderId, Map.of("status", OrderStatus.FINANCIAL_RECONCILIATION_REQUIRED,
                        "saga_phase", "FINANCIAL_RECONCILIATION"));
            }
            ObligationType type = ObligationType.valueOf(obligationType);
            String operation = type == ObligationType.POST_CAPTURE_INVENTORY_REPAIR ? "INVENTORY_COMMIT" : "PROMOTION_CONFIRM";
            List<ReconciliationLedger.Evidence> evidence = checkpoints.forOrder(orderId).stream()
                    .filter(c -> c.operation().equals(operation))
                    .map(c -> new ReconciliationLedger.Evidence("ORDER_CHECKPOINT", "checkpoint/" + c.commandId(),
                            c.requestDigest(), null, null))
                    .toList();
            reconciliation.open(orderId, type, operation.equals("INVENTORY_COMMIT") ? "inventory-commit" : detail,
                    detail, evidence);
        });
    }

    /** T8 / T8.5: PROCESSING once no post-capture repair obligation remains, then converge. */
    public boolean completePostPayment(long orderId) {
        Boolean done = tx.execute(s -> {
            OrderRow order = orders.lock(orderId).orElseThrow();
            if (reconciliation.hasOpenPostCaptureRepair(orderId)) {
                return false;
            }
            if (order.status() == OrderStatus.PAID || order.status() == OrderStatus.FINANCIAL_RECONCILIATION_REQUIRED) {
                orders.update(orderId, Map.of("status", OrderStatus.PROCESSING, "saga_phase", "FULFILLMENT"));
                meters.counter("order.lifecycle", "transition", "PROCESSING").increment();
            }
            convergence.tryComplete(orderId);
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    // ------------------------------------------------------------------ Next payment attempt (WF-ORDER-011)

    public NextAttempt prepareNextAttempt(long orderId, String operationId, String returnUrl) {
        return tx.execute(s -> {
            OrderRow order = orders.lock(orderId).orElseThrow();
            Instant now = clock.instant();
            String reason = nextAttemptBlocker(order, now);
            UUID operation = UUID.fromString(operationId);
            if (reason != null) {
                operations.transition(operation, OperationTypes.Status.REJECTED, null,
                        json.write(problems.operationProblem("PAYMENT_RETRY_NOT_ELIGIBLE", reason)));
                return new NextAttempt(false, 0, null);
            }
            operations.transition(operation, OperationTypes.Status.RUNNING, null, null);
            int next = attempts.list(orderId).stream().mapToInt(AttemptRow::attemptNumber).max().orElse(0) + 1;
            return new NextAttempt(true, next, returnUrl);
        });
    }

    /** Null when eligible; otherwise the reason (INV-ORD-028). Caller holds the order lock. */
    public String nextAttemptBlocker(OrderRow order, Instant now) {
        if (order.status() != OrderStatus.PENDING_PAYMENT) {
            return "Order is " + order.status();
        }
        if (order.capturedTransactionId() != null) {
            return "Payment already captured";
        }
        if (order.paymentDeadlineLockedAt() == null || !order.paymentDeadlineAt().isAfter(now)) {
            return "Payment deadline has passed";
        }
        if (order.activeAttemptNumber() == null) {
            return "No previous attempt";
        }
        String last = attempts.find(order.id(), order.activeAttemptNumber()).map(AttemptRow::terminalStatus).orElse(null);
        if (!"FAILED".equals(last)) {
            return "Previous attempt is " + (last == null ? "PENDING" : last);
        }
        if (!holdsValid(order, now)) {
            return "Inventory or Promotion hold is no longer accepted";
        }
        return null;
    }

    private boolean holdsValid(OrderRow order, Instant now) {
        Optional<CheckpointRow> reserve = checkpoints.find(reserveKey(order.id()));
        boolean releaseStarted = checkpoints.find(
                new CheckpointStore.Key(order.id(), LANE, "CANCELLING", "INVENTORY_RELEASE", "", 0)).isPresent();
        if (reserve.isEmpty() || !reserve.get().accepted() || releaseStarted) {
            return false;
        }
        for (PromotionRow p : orders.promotions(order.id())) {
            if (p.reservationExpiresAt() == null || !p.reservationExpiresAt().isAfter(now)) {
                return false;
            }
        }
        return true;
    }

    public void finishAttemptOperation(long orderId, String operationId, int attemptNumber, String outcome) {
        tx.executeWithoutResult(s -> {
            UUID operation = UUID.fromString(operationId);
            OrderRow order = orders.lock(orderId).orElseThrow();
            boolean deadlinePassed = !order.paymentDeadlineAt().isAfter(clock.instant());
            if ("ACCEPTED".equals(outcome) && !deadlinePassed && order.status() == OrderStatus.PENDING_PAYMENT) {
                operations.transition(operation, OperationTypes.Status.SUCCEEDED,
                        "/api/v1/orders/" + orderId + "/payment-attempts/" + attemptNumber, null);
            } else if ("ACCEPTED".equals(outcome)) {
                operations.transition(operation, OperationTypes.Status.REJECTED, null, json.write(
                        problems.operationProblem("PAYMENT_RETRY_NOT_ELIGIBLE", "Payment deadline reached")));
            } else if ("UNKNOWN".equals(outcome)) {
                operations.transition(operation, OperationTypes.Status.MANUAL_REVIEW_REQUIRED, null, json.write(
                        problems.operationProblem("DEPENDENCY_UNAVAILABLE", "Payment initiation outcome unknown")));
            } else {
                operations.transition(operation, OperationTypes.Status.REJECTED, null, json.write(
                        problems.operationProblem("PAYMENT_INITIATION_REJECTED", "Payment rejected the attempt")));
            }
        });
    }

    public void rejectOperation(String operationId, String code, String detail) {
        tx.executeWithoutResult(s -> operations.transition(UUID.fromString(operationId),
                OperationTypes.Status.REJECTED, null, json.write(problems.operationProblem(code, detail))));
    }

    // ------------------------------------------------------------------ helpers

    private void rejectOpen(long orderId, OperationTypes.Type type, String detail) {
        operations.openByOrder(orderId, type).forEach(op -> operations.transition(op.operationId(),
                OperationTypes.Status.REJECTED, null,
                json.write(problems.operationProblem("INVALID_ORDER_TRANSITION", detail))));
    }

    private void anomaly(long orderId, String txn, String detail) {
        reconciliation.open(orderId, ObligationType.CAPTURE_AFTER_TERMINAL_NON_CAPTURE, txn, detail,
                List.of(new ReconciliationLedger.Evidence("PAYMENT_RECONCILIATION", "payment/" + txn,
                        com.ecommerce.order.common.CanonicalJson.sha256Hex(txn + ":" + detail), null, null)));
        meters.counter("order.capture_after_terminal_non_capture").increment();
    }

    static CheckpointStore.Key reserveKey(long orderId) {
        return new CheckpointStore.Key(orderId, LANE, "PRE_PAYMENT", "INVENTORY_RESERVE", "", 0);
    }

    private static Gateways.Command command(OrderRow order, CheckpointRow cp) {
        return new Gateways.Command(cp.commandId(), WorkflowNames.checkout(order.checkoutToken()), cp.commandId());
    }

    private static void money(ObjectNode node, long amountMinor) {
        node.put("amount_minor", amountMinor);
        node.put("currency", "VND");
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Outcome outcome(RemoteResult result) {
        return switch (result.kind()) {
            case ACCEPTED -> Outcome.accepted();
            case REJECTED -> Outcome.rejected(result.errorCode());
            case UNKNOWN -> Outcome.unknown(result.errorCode());
        };
    }
}
