package com.ecommerce.order.service;

import com.ecommerce.order.common.ApiException;
import com.ecommerce.order.common.ErrorCode;
import com.ecommerce.order.common.Ids;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.domain.CancelReason;
import com.ecommerce.order.domain.FulfillmentStatus;
import com.ecommerce.order.domain.OperationTypes;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.domain.RefundTypes.CaseStatus;
import com.ecommerce.order.domain.RefundTypes.FinalizationStatus;
import com.ecommerce.order.domain.RefundTypes.Mode;
import com.ecommerce.order.store.OperationStore;
import com.ecommerce.order.store.OperationStore.OperationRow;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.OrderStore.LineRow;
import com.ecommerce.order.store.OrderStore.OrderRow;
import com.ecommerce.order.store.RefundStore;
import com.ecommerce.order.web.Caller;
import com.ecommerce.order.workflow.WorkflowMessage;
import com.ecommerce.order.workflow.WorkflowNames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Public commands (WF-ORDER-003/007/011): each validates under the order row lock,
 * persists its operation plus the durable workflow dispatch/message in one transaction
 * and returns {@code 202}. Idempotency is scoped by (principal, operation type, key) with a
 * canonical digest that includes the target order; Redis only mirrors replay.
 */
@Service
public class OrderCommandService {

    private final OrderStore orders;
    private final OperationStore operations;
    private final RefundStore refunds;
    private final CheckoutSaga checkout;
    private final WorkflowEnqueuer enqueuer;
    private final RequestMirror mirror;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Json json;
    private final Clock clock;

    public OrderCommandService(OrderStore orders, OperationStore operations, RefundStore refunds,
                               CheckoutSaga checkout, WorkflowEnqueuer enqueuer, RequestMirror mirror,
                               NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, Json json, Clock clock) {
        this.orders = orders;
        this.operations = operations;
        this.refunds = refunds;
        this.checkout = checkout;
        this.enqueuer = enqueuer;
        this.mirror = mirror;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    private interface Accept {
        /** Runs under the order lock after idempotency lookup; returns extra response fields. */
        Map<String, String> apply(OrderRow order, UUID operationId);
    }

    // ------------------------------------------------------------------ Cancellation (WF-ORDER-003 branch A)

    public ResponseEntity<Object> cancel(String orderIdValue, Caller caller, String key, JsonNode body) {
        OperationTypes.Type type = caller.admin() ? OperationTypes.Type.ADMIN_CANCELLATION
                : OperationTypes.Type.BUYER_CANCELLATION;
        return command(orderIdValue, caller, key, body, type, "cancel", (order, operationId) -> {
            if (order.status() != OrderStatus.PENDING_PAYMENT) {
                throw new ApiException(ErrorCode.INVALID_ORDER_TRANSITION, caller.admin()
                        && OrderStatus.CAPTURED.contains(order.status())
                        ? "Captured orders use the pre-dispatch refund endpoint"
                        : "Only a PENDING_PAYMENT order can be cancelled");
            }
            CancelReason reason = caller.admin() ? CancelReason.ADMIN_FORCE_CANCEL : CancelReason.USER_CANCEL;
            orders.update(order.id(), Map.of("status", OrderStatus.CANCELLING, "cancel_reason", reason.name(),
                    "saga_phase", "CANCELLING"));
            enqueuer.message(order.id(), enqueuer.checkout(order.checkoutToken()), "api:" + operationId,
                    WorkflowMessage.CANCEL_ORDER, Map.of("operation_id", operationId.toString()));
            return Map.of();
        });
    }

    // ------------------------------------------------------------------ Buyer return (WF-ORDER-007)

    public ResponseEntity<Object> requestReturn(String orderIdValue, Caller caller, String key, JsonNode body) {
        return command(orderIdValue, caller, key, body, OperationTypes.Type.BUYER_RETURN, "return",
                (order, operationId) -> {
                    if (refunds.find(order.id()).isPresent() || order.buyerReturnLockedAt() != null) {
                        throw new ApiException(order.buyerReturnLockedAt() != null
                                ? ErrorCode.REFUND_CUSTOMER_REQUEST_ALREADY_USED : ErrorCode.REFUND_CASE_ALREADY_EXISTS,
                                "The order's lifetime return/refund entitlement is already used");
                    }
                    List<LineRow> lines = orders.lockLines(order.id());
                    if (lines.stream().anyMatch(l -> l.fulfillmentStatus() != FulfillmentStatus.DELIVERED)) {
                        throw new ApiException(ErrorCode.REFUND_REQUIRES_ALL_ITEMS_DELIVERED,
                                "Every order line must be delivered by verified Delivery proof");
                    }
                    if (order.status() != OrderStatus.COMPLETED) {
                        throw new ApiException(ErrorCode.INVALID_ORDER_TRANSITION, "Only a COMPLETED order can be returned");
                    }
                    List<RefundStore.CaseLineRow> scope = scope(lines, body.path("lines"));
                    int locked = jdbc.update("""
                            UPDATE orders SET buyer_return_locked_at = NOW()
                            WHERE id = :id AND buyer_return_locked_at IS NULL
                            """, Map.of("id", order.id()));
                    if (locked != 1) {
                        throw new ApiException(ErrorCode.REFUND_CUSTOMER_REQUEST_ALREADY_USED,
                                "The Buyer return request was already used");
                    }
                    String refundRequestId = "rfr_" + Ids.newUuid();
                    long amount = scope.stream().mapToLong(RefundStore.CaseLineRow::netAmountMinor).sum();
                    String reason = body.path("reason_code").asText();
                    refunds.insertCase(new RefundStore.NewCase(order.id(), refundRequestId,
                            WorkflowNames.buyerRefund(order.id()), "API_RETURN", reason,
                            body.path("reason_details").isTextual() ? body.path("reason_details").asText() : null,
                            caller.principal(), key, digest(order.id(), body), operationId, CaseStatus.INITIATED,
                            Mode.BUYER_RETURN_SELECTED_LINES, amount, FinalizationStatus.NOT_REQUESTED, clock.instant()));
                    scope.forEach(line -> refunds.insertCaseLine(order.id(), line));
                    orders.update(order.id(), Map.of("status", OrderStatus.RETURN_REQUESTED, "return_reason", reason,
                            "saga_phase", "REFUND"));
                    enqueuer.dispatch(order.id(), enqueuer.buyerRefund(order.id()));
                    return Map.of("refund_request_id", refundRequestId);
                });
    }

    /** Selected full-quantity lines (INV-ORD-015); immutable net snapshot copied under lock. */
    private List<RefundStore.CaseLineRow> scope(List<LineRow> lines, JsonNode requested) {
        if (!requested.isArray() || requested.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REFUND_SCOPE, "lines must not be empty");
        }
        Map<Long, LineRow> byId = new HashMap<>();
        lines.forEach(l -> byId.put(l.id(), l));
        Set<Long> seen = new HashSet<>();
        List<RefundStore.CaseLineRow> scope = new ArrayList<>();
        List<ApiException.FieldError> errors = new ArrayList<>();
        for (JsonNode item : requested) {
            Long lineId = Ids.parseCanonical(item.path("order_line_id").asText(null));
            LineRow line = lineId == null ? null : byId.get(lineId);
            if (line == null) {
                errors.add(new ApiException.FieldError("lines", "order_line_id does not belong to the order"));
                continue;
            }
            if (!seen.add(lineId)) {
                errors.add(new ApiException.FieldError("lines", "order_line_id " + lineId + " is duplicated"));
                continue;
            }
            if (item.path("quantity").asInt(-1) != line.quantity()) {
                errors.add(new ApiException.FieldError("lines", "quantity of line " + lineId
                        + " must equal the full ordered quantity " + line.quantity()));
                continue;
            }
            scope.add(new RefundStore.CaseLineRow(line.id(), line.quantity(), line.unitPriceMinor(),
                    line.discountShareMinor(), line.netAmountMinor()));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REFUND_SCOPE, "Invalid refund scope", errors, null, Map.of());
        }
        return scope;
    }

    // ------------------------------------------------------------------ Admin pre-dispatch refund (branch B)

    public ResponseEntity<Object> adminPreDispatchRefund(String orderIdValue, Caller caller, String key, JsonNode body) {
        return command(orderIdValue, caller, key, body, OperationTypes.Type.ADMIN_PRE_DISPATCH_REFUND, "admin-refund",
                (order, operationId) -> {
                    if (order.status() != OrderStatus.PAID && order.status() != OrderStatus.PROCESSING) {
                        throw new ApiException(ErrorCode.INVALID_ORDER_TRANSITION,
                                "Automatic pre-dispatch refund requires PAID or PROCESSING");
                    }
                    if (refunds.find(order.id()).isPresent()) {
                        throw new ApiException(ErrorCode.REFUND_CASE_ALREADY_EXISTS,
                                "The order already has its lifetime refund case");
                    }
                    if (refunds.activeAdminAttempt(order.id()).isPresent()) {
                        throw new ApiException(ErrorCode.OPERATION_IN_PROGRESS,
                                "Another pre-dispatch release attempt is running");
                    }
                    UUID attemptId = Ids.newUuid();
                    refunds.insertAdminAttempt(new RefundStore.AdminAttemptRow(attemptId, order.id(),
                            WorkflowNames.adminRefund(attemptId), "order-" + order.id() + "-predispatch-release-" + attemptId,
                            operationId, digest(order.id(), body), body.path("reason_code").asText(),
                            body.path("reason_details").isTextual() ? body.path("reason_details").asText() : null,
                            caller.principal(), "PENDING", null, null, null));
                    enqueuer.dispatch(order.id(), enqueuer.adminRefund(order.id(), attemptId));
                    return Map.of();
                });
    }

    // ------------------------------------------------------------------ Next payment attempt (WF-ORDER-011)

    public ResponseEntity<Object> createPaymentAttempt(String orderIdValue, Caller caller, String key, JsonNode body) {
        return command(orderIdValue, caller, key, body, OperationTypes.Type.PAYMENT_ATTEMPT, "payment-attempt",
                (order, operationId) -> {
                    String blocker = checkout.nextAttemptBlocker(order, clock.instant());
                    if (blocker != null) {
                        throw new ApiException(ErrorCode.PAYMENT_RETRY_NOT_ELIGIBLE, blocker);
                    }
                    if (operations.openByOrder(order.id(), OperationTypes.Type.PAYMENT_ATTEMPT).stream()
                            .anyMatch(op -> !op.operationId().equals(operationId))) {
                        throw new ApiException(ErrorCode.OPERATION_IN_PROGRESS, "A payment attempt request is running");
                    }
                    Map<String, String> attrs = new LinkedHashMap<>();
                    attrs.put("operation_id", operationId.toString());
                    JsonNode returnUrl = body.path("return_url");
                    if (returnUrl.isTextual()) {
                        attrs.put("return_url", returnUrl.asText());
                    }
                    enqueuer.message(order.id(), enqueuer.checkout(order.checkoutToken()), "api:" + operationId,
                            WorkflowMessage.NEXT_PAYMENT_ATTEMPT, attrs);
                    return Map.of();
                });
    }

    // ------------------------------------------------------------------ shared idempotent acceptance

    private ResponseEntity<Object> command(String orderIdValue, Caller caller, String key, JsonNode body,
                                           OperationTypes.Type type, String action, Accept accept) {
        validateKey(key);
        Long orderId = Ids.parseCanonical(orderIdValue);
        if (orderId == null) {
            throw ApiException.notFound();
        }
        String digest = digest(orderId, body);
        var mirrored = mirror.get(caller.principal(), action, key);
        if (mirrored.isPresent()) {
            if (!mirrored.get().digest().equals(digest)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key reused with a different payload");
            }
            var replay = operations.find(UUID.fromString(mirrored.get().operationId()));
            if (replay.isPresent()) {
                return accepted(replay.get(), caller, Map.of());
            }
        }
        for (int attempt = 0; ; attempt++) {
            try {
                Result result = tx.execute(s -> {
                    var existing = operations.findByScope(caller.principal(), type, key);
                    if (existing.isPresent()) {
                        if (!existing.get().requestDigest().equals(digest)) {
                            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                                    "Idempotency-Key reused with a different payload");
                        }
                        return new Result(existing.get(), replayExtras(existing.get()));
                    }
                    OrderRow order = orders.lock(orderId).orElseThrow(ApiException::notFound);
                    if (!caller.admin() && !order.userId().equals(caller.userId())) {
                        throw ApiException.notFound();
                    }
                    UUID operationId = Ids.newUuid();
                    operations.insert(operationId, orderId, caller.principal(), caller.role(), type, key, digest);
                    Map<String, String> extras = accept.apply(order, operationId);
                    return new Result(operations.find(operationId).orElseThrow(), extras);
                });
                mirror.put(caller.principal(), action, key, digest, result.operation().operationId().toString());
                return accepted(result.operation(), caller, result.extras());
            } catch (DuplicateKeyException race) {
                if (attempt >= 1) {
                    throw race;
                }
            }
        }
    }

    private record Result(OperationRow operation, Map<String, String> extras) {
    }

    private Map<String, String> replayExtras(OperationRow operation) {
        if (operation.type() == OperationTypes.Type.BUYER_RETURN) {
            return refunds.find(operation.orderId()).map(c -> Map.of("refund_request_id", c.refundRequestId()))
                    .orElse(Map.of());
        }
        return Map.of();
    }

    private ResponseEntity<Object> accepted(OperationRow operation, Caller caller, Map<String, String> extras) {
        String statusUrl = (caller.admin() ? "/api/v1/admin/order-operations/" : "/api/v1/order-operations/")
                + operation.operationId();
        ObjectNode body = json.object();
        body.put("operation_id", operation.operationId().toString());
        body.put("operation_type", operation.type().name());
        body.put("order_id", Ids.wire(operation.orderId()));
        body.put("status", "ACCEPTED");
        body.put("status_url", statusUrl);
        body.put("created_at", operation.createdAt().toString());
        extras.forEach(body::put);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, statusUrl)
                .header(HttpHeaders.RETRY_AFTER, "2")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private String digest(long orderId, JsonNode body) {
        ObjectNode envelope = json.object();
        envelope.put("order_id", Long.toString(orderId));
        envelope.set("body", body == null ? json.mapper().nullNode() : body);
        return json.digest(envelope);
    }

    private static void validateKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128) {
            throw ApiException.invalid("Idempotency-Key must be 16..128 characters");
        }
    }
}
