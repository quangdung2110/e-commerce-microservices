package com.ecommerce.order.service;

import com.ecommerce.order.client.Gateways;
import com.ecommerce.order.client.RemoteResult;
import com.ecommerce.order.common.CanonicalJson;
import com.ecommerce.order.common.Ids;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.domain.OperationTypes;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.domain.PaymentStatus;
import com.ecommerce.order.domain.RefundTypes.CaseStatus;
import com.ecommerce.order.domain.RefundTypes.FinalizationOperation;
import com.ecommerce.order.domain.RefundTypes.FinalizationStatus;
import com.ecommerce.order.domain.RefundTypes.Mode;
import com.ecommerce.order.domain.RefundTypes.ObligationType;
import com.ecommerce.order.store.CheckpointStore;
import com.ecommerce.order.store.OperationStore;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.OrderStore.OrderRow;
import com.ecommerce.order.store.RefundStore;
import com.ecommerce.order.store.RefundStore.AdminAttemptRow;
import com.ecommerce.order.store.RefundStore.CaseLineRow;
import com.ecommerce.order.store.RefundStore.CaseRow;
import com.ecommerce.order.workflow.ActivityResults.Outcome;
import com.ecommerce.order.workflow.ActivityResults.RefundState;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Activities of OrderRefundWorkflow (WF-ORDER-008): one lifetime case, Buyer selected
 * full-quantity scope gated by Inventory inspection completion, Admin pre-dispatch gated by
 * Inventory's atomic all-line release, exactly one Payment refund, and Buyer scope
 * finalization frozen with the financial result (ADR-010/013). Never calls Promotion.
 */
@Service
public class RefundSaga {

    private static final Logger log = LoggerFactory.getLogger(RefundSaga.class);
    static final String LANE = "REFUND";

    private final OrderStore orders;
    private final RefundStore refunds;
    private final OperationStore operations;
    private final RemoteCommands remote;
    private final Gateways.Inventory inventory;
    private final Gateways.Payment payment;
    private final ReconciliationLedger reconciliation;
    private final ScopeFinalization finalization;
    private final NotificationComposer notifications;
    private final ProblemFactory problems;
    private final InboxReplayer replayer;
    private final TransactionTemplate tx;
    private final Json json;
    private final Clock clock;
    private final OrderProperties.Refund config;
    private final MeterRegistry meters;

    public RefundSaga(OrderStore orders, RefundStore refunds, OperationStore operations, RemoteCommands remote,
                      Gateways.Inventory inventory, Gateways.Payment payment, ReconciliationLedger reconciliation,
                      ScopeFinalization finalization, NotificationComposer notifications, ProblemFactory problems,
                      InboxReplayer replayer, TransactionTemplate tx, Json json, Clock clock,
                      OrderProperties properties, MeterRegistry meters) {
        this.orders = orders;
        this.refunds = refunds;
        this.operations = operations;
        this.remote = remote;
        this.inventory = inventory;
        this.payment = payment;
        this.reconciliation = reconciliation;
        this.finalization = finalization;
        this.notifications = notifications;
        this.problems = problems;
        this.replayer = replayer;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.config = properties.refund();
        this.meters = meters;
    }

    /** Maps durable state to the workflow phase so a run can (re)enter correctly. */
    public RefundState load(long orderId, String mode, String attemptId) {
        if ("ADMIN".equals(mode)) {
            AdminAttemptRow attempt = refunds.adminAttempt(UUID.fromString(attemptId)).orElseThrow();
            if ("REJECTED".equals(attempt.status())) {
                return new RefundState("DONE", 0);
            }
            if (!"ACCEPTED".equals(attempt.status())) {
                return new RefundState("RELEASE", 0);
            }
        }
        CaseRow refundCase = refunds.find(orderId).orElseThrow();
        long deadline = refundCase.startedAt().plus(config.inspectionWait()).toEpochMilli();
        String phase = switch (refundCase.status()) {
            case INITIATED -> refundCase.mode() == Mode.ADMIN_PRE_DISPATCH_FULL_ORDER ? "PAY" : "REGISTER";
            case WAITING_INVENTORY_SCOPE_INSPECTION -> refundCase.inspectionEventId() != null ? "PAY" : "WAIT_INSPECTION";
            case WAITING_INVENTORY_RELEASE -> "RELEASE";
            case WAITING_PAYMENT_REFUND_RESULT -> refundCase.refundTransactionId() == null ? "PAY" : "WAIT_RESULT";
            case MANUAL_VERIFICATION_REQUIRED -> refundCase.refundTransactionId() == null ? "MANUAL" : "DONE";
            default -> "DONE";
        };
        return new RefundState(phase, deadline);
    }

    // ------------------------------------------------------------------ Buyer scope

    public Outcome registerScope(long orderId) {
        CaseRow refundCase = refunds.find(orderId).orElseThrow();
        if (refundCase.refundScopeId() != null) {
            return Outcome.accepted();
        }
        ObjectNode body = json.object();
        body.put("order_id", Ids.wire(orderId));
        body.put("refund_request_id", refundCase.refundRequestId());
        ArrayNode lines = body.putArray("lines");
        Map<Long, String> skus = new HashMap<>();
        orders.lines(orderId).forEach(l -> skus.put(l.id(), l.skuId()));
        for (CaseLineRow line : refunds.lines(orderId)) {
            lines.addObject().put("order_line_id", Ids.wire(line.orderLineId())).put("sku_id", skus.get(line.orderLineId()))
                    .put("expected_quantity", line.expectedQuantity());
        }
        RemoteResult result = remote.execute(new CheckpointStore.Key(orderId, LANE, "SCOPE", "SCOPE_REGISTER", "", 0),
                "order-" + orderId + "-refund-scope-register", body,
                cp -> inventory.registerRefundScope(new Gateways.Command(cp.commandId(), refundCase.workflowId(),
                        cp.commandId()), json.read(cp.requestJson())),
                r -> "REGISTERED".equals(r.body().path("outcome").asText())
                        && refundCase.refundRequestId().equals(r.body().path("refund_request_id").asText())
                        && Ids.wire(orderId).equals(r.body().path("order_id").asText())
                        ? r : RemoteResult.rejected(r.httpStatus(), "RECEIPT_MISMATCH", r.body()),
                r -> {
                    if (r.isAccepted()) {
                        orders.lock(orderId);
                        refunds.lock(orderId);
                        refunds.update(orderId, Map.of("inventory_refund_scope_id", r.body().path("refund_scope_id").asText(),
                                "inventory_scope_registered_revision", r.body().path("scope_revision").asLong(),
                                "case_status", CaseStatus.WAITING_INVENTORY_SCOPE_INSPECTION));
                        running(refundCase);
                    }
                });
        if (result.isRejected()) {
            enterManualVerification(orderId, "SCOPE_REGISTRATION_REJECTED:" + result.errorCode());
        }
        return outcome(result);
    }

    /** 30-day deadline or durable scope rejection: same case, manual lane, no Payment bypass. */
    public void enterManualVerification(long orderId, String reason) {
        tx.executeWithoutResult(s -> {
            orders.lock(orderId);
            CaseRow refundCase = refunds.lock(orderId).orElseThrow();
            if (refundCase.status() != CaseStatus.INITIATED
                    && refundCase.status() != CaseStatus.WAITING_INVENTORY_SCOPE_INSPECTION) {
                return;
            }
            refunds.update(orderId, Map.of("case_status", CaseStatus.MANUAL_VERIFICATION_REQUIRED,
                    "manual_verification_reason", reason.length() > 64 ? reason.substring(0, 64) : reason));
            reconciliation.open(orderId, ObligationType.RETURN_MANUAL_VERIFICATION, refundCase.refundRequestId(), reason,
                    List.of());
            manualReview(refundCase, "Return requires manual verification: " + reason);
            meters.counter("order.refund.manual_verification").increment();
        });
    }

    /** READY, INITIATED (Payment command already frozen) or BLOCKED. */
    public String checkGate(long orderId) {
        CaseRow refundCase = refunds.find(orderId).orElseThrow();
        if (refundCase.refundCommandId() != null) {
            return "INITIATED";
        }
        if (refundCase.status().resolved()) {
            return "BLOCKED";
        }
        boolean noPayoutIntent = refundCase.finalizationStatus() != FinalizationStatus.NOT_REQUESTED
                && refundCase.finalizationStatus() != FinalizationStatus.NOT_REQUIRED;
        if (noPayoutIntent) {
            return "BLOCKED";
        }
        boolean evidence = refundCase.mode() == Mode.ADMIN_PRE_DISPATCH_FULL_ORDER
                ? refundCase.releaseCommandId() != null : refundCase.inspectionEventId() != null;
        return evidence ? "READY" : "BLOCKED";
    }

    // ------------------------------------------------------------------ Payment refund

    public Outcome initiateRefund(long orderId) {
        Boolean allowed = tx.execute(s -> {
            orders.lock(orderId);
            CaseRow refundCase = refunds.lock(orderId).orElseThrow();
            if (refundCase.refundCommandId() != null) {
                return true;
            }
            if (!"READY".equals(checkGate(orderId))) {
                return false;
            }
            refunds.update(orderId, Map.of("refund_command_id", refundCommandId(orderId, refundCase),
                    "case_status", CaseStatus.WAITING_PAYMENT_REFUND_RESULT));
            orders.update(orderId, Map.of("payment_status", PaymentStatus.REFUNDING));
            running(refundCase);
            return true;
        });
        if (!Boolean.TRUE.equals(allowed)) {
            return Outcome.rejected("REFUND_GATE_BLOCKED");
        }
        CaseRow refundCase = refunds.find(orderId).orElseThrow();
        OrderRow order = orders.find(orderId).orElseThrow();
        ObjectNode body = json.object();
        body.put("order_id", Ids.wire(orderId));
        body.put("refund_request_id", refundCase.refundRequestId());
        ObjectNode amount = body.putObject("amount");
        amount.put("amount_minor", refundCase.amountLockedMinor());
        amount.put("currency", "VND");
        ObjectNode evidence = body.putObject("inventory_evidence");
        if (refundCase.mode() == Mode.ADMIN_PRE_DISPATCH_FULL_ORDER) {
            body.put("reason", "ADMIN_FORCE_REFUND_BEFORE_DISPATCH");
            evidence.put("type", "COMMITTED_ORDER_RELEASED_BEFORE_DISPATCH");
            evidence.put("release_reference", refundCase.releaseCommandId());
        } else {
            body.put("reason", "CUSTOMER_RETURN_SELECTED_LINES");
            evidence.put("type", "RETURN_SCOPE_INSPECTION_COMPLETED");
            evidence.put("refund_scope_id", refundCase.refundScopeId());
            long expected = 0;
            long received = 0;
            long passed = 0;
            long failed = 0;
            for (JsonNode line : json.read(refundCase.inspectionLinesJson())) {
                expected += line.path("expected_quantity").asLong();
                received += line.path("received_quantity").asLong();
                passed += line.path("passed_quantity").asLong();
                failed += line.path("failed_quantity").asLong();
            }
            evidence.put("expected", expected).put("received", received).put("passed", passed).put("failed", failed);
        }
        String captured = order.capturedTransactionId();
        RemoteResult result = remote.execute(
                new CheckpointStore.Key(orderId, LANE, "PAYMENT", "PAYMENT_REFUND", refundCase.refundRequestId(), 0),
                refundCase.refundCommandId(), body,
                cp -> payment.refund(captured, cp.commandId(), refundCase.workflowId(), json.read(cp.requestJson())),
                r -> captured.equals(r.body().path("payment_transaction_id").asText())
                        && r.body().path("refund_transaction_id").isTextual()
                        ? r : RemoteResult.rejected(r.httpStatus(), "RECEIPT_MISMATCH", r.body()),
                r -> {
                    if (r.isAccepted()) {
                        orders.lock(orderId);
                        refunds.lock(orderId);
                        refunds.update(orderId, Map.of("refund_transaction_id",
                                r.body().path("refund_transaction_id").asText()));
                    }
                });
        if (result.isAccepted()) {
            replayer.replayIdentityBind(orderId);
        } else if (result.isRejected()) {
            payoutRemediation(orderId, "REFUND_COMMAND_REJECTED:" + result.errorCode());
        }
        return outcome(result);
    }

    /** Applies a validated RefundResult (T10/T10.1/T10.2); returns the resulting financial state. */
    public String applyResult(long orderId, Map<String, String> attrs) {
        return tx.execute(s -> {
            OrderRow order = orders.lock(orderId).orElseThrow();
            CaseRow refundCase = refunds.lock(orderId).orElseThrow();
            String refundTxn = attrs.get("refund_transaction_id");
            if (!refundTxn.equals(refundCase.refundTransactionId())) {
                return "IGNORED";
            }
            Instant occurredAt = Instant.parse(attrs.get("occurred_at"));
            return switch (attrs.get("result")) {
                case "PROCESSING" -> {
                    if (refundCase.status() == CaseStatus.WAITING_PAYMENT_REFUND_RESULT) {
                        orders.update(orderId, Map.of("payment_status", PaymentStatus.REFUNDING));
                    }
                    yield "PROCESSING";
                }
                case "SUCCEEDED" -> succeeded(order, refundCase, refundTxn, occurredAt);
                case "FAILED" -> failed(order, refundCase, attrs.get("error_code"));
                case "REVERSED" -> reversed(order, refundCase, attrs);
                default -> "IGNORED";
            };
        });
    }

    private String succeeded(OrderRow order, CaseRow refundCase, String refundTxn, Instant occurredAt) {
        long orderId = order.id();
        if (refundCase.status() == CaseStatus.SUCCEEDED) {
            return "SUCCEEDED";
        }
        if (refundCase.status() != CaseStatus.WAITING_PAYMENT_REFUND_RESULT) {
            // Contradictory success after a terminal/external outcome is a new anomaly (ADR-019).
            reconciliation.open(orderId, ObligationType.CUSTOMER_PAYOUT_REMEDIATION, "late-success-" + refundTxn,
                    "SUCCESS_AFTER_" + refundCase.status(), List.of(new ReconciliationLedger.Evidence(
                            "PAYMENT_RECONCILIATION", "refund/" + refundTxn + "/succeeded",
                            CanonicalJson.sha256Hex(refundTxn + ":SUCCEEDED"), null, null)));
            return "IGNORED";
        }
        Instant now = clock.instant();
        refunds.update(orderId, Map.of("case_status", CaseStatus.SUCCEEDED, "resolved_at", now));
        orders.update(orderId, Map.of("status", OrderStatus.REFUNDED, "payment_status", PaymentStatus.REFUNDED,
                "refunded_at", occurredAt, "saga_phase", "REFUND_FINALIZATION"));
        notifications.orderRefunded(orders.find(orderId).orElseThrow(), refundTxn, refundCase.amountLockedMinor(),
                occurredAt);
        if (refundCase.mode() == Mode.BUYER_RETURN_SELECTED_LINES) {
            freezeCloseIfAbsent(refundCase, "REFUNDED");
        } else if (refundCase.operationId() != null) {
            operations.transition(refundCase.operationId(), OperationTypes.Status.SUCCEEDED,
                    "/api/v1/orders/" + orderId + "/refund-case", null);
        }
        meters.counter("order.refund.result", "outcome", "SUCCEEDED").increment();
        return "SUCCEEDED";
    }

    private String failed(OrderRow order, CaseRow refundCase, String errorCode) {
        long orderId = order.id();
        if (refundCase.status() != CaseStatus.WAITING_PAYMENT_REFUND_RESULT) {
            return refundCase.status().name();
        }
        Instant now = clock.instant();
        Map<String, Object> changes = new HashMap<>();
        changes.put("case_status", CaseStatus.FAILED);
        changes.put("resolved_at", now);
        changes.put("refund_failure_code", errorCode);
        refunds.update(orderId, changes);
        orders.update(orderId, Map.of("payment_status", PaymentStatus.REFUND_FAILED));
        reconciliation.open(orderId, ObligationType.CUSTOMER_PAYOUT_REMEDIATION, refundCase.refundTransactionId(),
                "REFUND_FAILED:" + errorCode, List.of(new ReconciliationLedger.Evidence("PAYMENT_RECONCILIATION",
                        "refund/" + refundCase.refundTransactionId() + "/failed",
                        CanonicalJson.sha256Hex(refundCase.refundTransactionId() + ":FAILED:" + errorCode), null, null)));
        if (refundCase.mode() == Mode.BUYER_RETURN_SELECTED_LINES) {
            freezeCloseIfAbsent(refundCase, "REJECTED");
        }
        manualReview(refundCase, "Refund failed; customer payout remediation required");
        meters.counter("order.refund.result", "outcome", "FAILED").increment();
        return "FAILED";
    }

    private String reversed(OrderRow order, CaseRow refundCase, Map<String, String> attrs) {
        long orderId = order.id();
        if (refundCase.status() != CaseStatus.SUCCEEDED && refundCase.status() != CaseStatus.WAITING_PAYMENT_REFUND_RESULT) {
            return refundCase.status().name();
        }
        Map<String, Object> changes = new HashMap<>();
        changes.put("case_status", CaseStatus.MANUAL_VERIFICATION_REQUIRED);
        changes.put("resolved_at", null);
        changes.put("refund_reversal_reason_code", attrs.get("reason_code"));
        refunds.update(orderId, changes);
        Map<String, Object> orderChanges = new HashMap<>();
        orderChanges.put("payment_status", PaymentStatus.REFUND_FAILED);
        if (order.status() == OrderStatus.REFUNDED) {
            orderChanges.put("status", OrderStatus.RETURN_REQUESTED);
            orderChanges.put("refunded_at", null);
        }
        orders.update(orderId, orderChanges);
        reconciliation.open(orderId, ObligationType.REFUND_REVERSAL, refundCase.refundTransactionId(),
                attrs.get("reason_code"), List.of(new ReconciliationLedger.Evidence("PAYMENT_RECONCILIATION",
                        "refund/" + refundCase.refundTransactionId() + "/reversed/" + attrs.get("event_id"),
                        attrs.getOrDefault("event_sha256", CanonicalJson.sha256Hex(attrs.toString())),
                        Long.parseLong(attrs.getOrDefault("financial_state_version", "0")), null)));
        if (refundCase.mode() == Mode.BUYER_RETURN_SELECTED_LINES) {
            freezeCloseIfAbsent(refundCase, "REJECTED");
        }
        manualReview(refundCase, "Refund reversed by the provider; manual verification required");
        meters.counter("order.refund.result", "outcome", "REVERSED").increment();
        return "REVERSED";
    }

    /** A reversal keeps an existing intent; the first terminal result freezes CLOSE. */
    private void freezeCloseIfAbsent(CaseRow refundCase, String reason) {
        if (refundCase.refundScopeId() != null && refundCase.finalizationStatus() == FinalizationStatus.NOT_REQUESTED) {
            finalization.freezeIntent(refunds.find(refundCase.orderId()).orElseThrow(), FinalizationOperation.CLOSE,
                    reason, null);
        }
    }

    // ------------------------------------------------------------------ Admin pre-dispatch

    public Outcome releaseBeforeDispatch(long orderId, String attemptIdValue) {
        UUID attemptId = UUID.fromString(attemptIdValue);
        AdminAttemptRow attempt = refunds.adminAttempt(attemptId).orElseThrow();
        if ("ACCEPTED".equals(attempt.status())) {
            return Outcome.accepted();
        }
        if ("REJECTED".equals(attempt.status())) {
            return Outcome.rejected(attempt.errorCode());
        }
        ObjectNode body = json.object();
        body.put("reason", "ADMIN_FORCE_REFUND_BEFORE_DISPATCH");
        String correlation = WorkflowNames.adminRefund(attemptId);
        RemoteResult result = remote.execute(
                new CheckpointStore.Key(orderId, "ADMIN_REFUND", "RELEASE", "INVENTORY_RELEASE_COMMITTED",
                        attemptIdValue, 0), attempt.inventoryCommandId(), body,
                cp -> inventory.releaseCommittedBeforeDispatch(Ids.wire(orderId),
                        new Gateways.Command(cp.commandId(), correlation, cp.commandId()), json.read(cp.requestJson())),
                r -> "RELEASED".equals(r.body().path("outcome").asText())
                        ? r : RemoteResult.rejected(r.httpStatus(), "RECEIPT_MISMATCH", r.body()),
                r -> {
                    if (r.isAccepted()) {
                        acceptRelease(orderId, attempt, r.body(), correlation);
                    } else {
                        rejectRelease(attempt, r.errorCode());
                    }
                });
        return outcome(result);
    }

    private void acceptRelease(long orderId, AdminAttemptRow attempt, JsonNode body, String correlation) {
        OrderRow order = orders.lock(orderId).orElseThrow();
        String digest = CanonicalJson.digest(body);
        refunds.updateAdminAttempt(attempt.attemptId(), "ACCEPTED", digest, null);
        if (refunds.find(orderId).isPresent()) {
            log.error("Order {} already has a lifetime case; accepted admin release {} recorded only", orderId,
                    attempt.attemptId());
            return;
        }
        OperationStore.OperationRow op = operations.find(attempt.operationId()).orElseThrow();
        refunds.insertCase(new RefundStore.NewCase(orderId, "rfr_" + Ids.newUuid(), attempt.workflowId(),
                "ADMIN_FORCE_REFUND", attempt.reasonCode(), attempt.reasonDetails(), op.ownerPrincipalId(),
                op.idempotencyKey(), op.requestDigest(), op.operationId(), CaseStatus.INITIATED,
                Mode.ADMIN_PRE_DISPATCH_FULL_ORDER, order.finalAmountMinor(), FinalizationStatus.NOT_REQUIRED,
                clock.instant()));
        refunds.recordAdminReleaseEvidence(orderId, attempt.inventoryCommandId(), correlation,
                body.path("reservation_revision").asLong(), json.write(body.path("released_order_line_ids")), digest,
                clock.instant());
        orders.update(orderId, Map.of("status", OrderStatus.RETURN_REQUESTED, "return_reason", attempt.reasonCode(),
                "saga_phase", "REFUND"));
        operations.transition(op.operationId(), OperationTypes.Status.RUNNING, null, null);
    }

    private void rejectRelease(AdminAttemptRow attempt, String code) {
        refunds.updateAdminAttempt(attempt.attemptId(), "REJECTED", null, code);
        operations.transition(attempt.operationId(), OperationTypes.Status.REJECTED, null, json.write(
                problems.operationProblem("ADMIN_REFUND_REQUIRES_ALL_UNDISPATCHED",
                        "Inventory could not atomically release every committed line before dispatch (" + code + ")")));
    }

    public void markAdminAttemptRecovery(String attemptId) {
        tx.executeWithoutResult(s -> {
            AdminAttemptRow attempt = refunds.adminAttempt(UUID.fromString(attemptId)).orElseThrow();
            if ("PENDING".equals(attempt.status())) {
                refunds.updateAdminAttempt(attempt.attemptId(), "RECOVERY_REQUIRED", null, null);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private void payoutRemediation(long orderId, String detail) {
        tx.executeWithoutResult(s -> {
            orders.lock(orderId);
            CaseRow refundCase = refunds.lock(orderId).orElseThrow();
            reconciliation.open(orderId, ObligationType.CUSTOMER_PAYOUT_REMEDIATION, refundCase.refundRequestId(),
                    detail, List.of());
            manualReview(refundCase, "Payment rejected the refund command");
        });
    }

    private void running(CaseRow refundCase) {
        if (refundCase.operationId() != null) {
            operations.transition(refundCase.operationId(), OperationTypes.Status.RUNNING, null, null);
        }
    }

    private void manualReview(CaseRow refundCase, String detail) {
        if (refundCase.operationId() != null) {
            operations.transition(refundCase.operationId(), OperationTypes.Status.MANUAL_REVIEW_REQUIRED, null,
                    json.write(problems.operationProblem("INVALID_ORDER_TRANSITION", detail)));
        }
    }

    private static String refundCommandId(long orderId, CaseRow refundCase) {
        return "order-" + orderId + "-refund-" + refundCase.refundRequestId();
    }

    private static Outcome outcome(RemoteResult result) {
        return switch (result.kind()) {
            case ACCEPTED -> Outcome.accepted();
            case REJECTED -> Outcome.rejected(result.errorCode());
            case UNKNOWN -> Outcome.unknown(result.errorCode());
        };
    }

    public Optional<CaseRow> refundCase(long orderId) {
        return refunds.find(orderId);
    }
}
