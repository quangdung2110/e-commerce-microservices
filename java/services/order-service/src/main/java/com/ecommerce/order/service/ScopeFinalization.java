package com.ecommerce.order.service;

import com.ecommerce.order.client.Gateways;
import com.ecommerce.order.client.RemoteResult;
import com.ecommerce.order.common.CanonicalJson;
import com.ecommerce.order.common.Ids;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.domain.OperationTypes;
import com.ecommerce.order.domain.RefundTypes.CaseStatus;
import com.ecommerce.order.domain.RefundTypes.FinalizationOperation;
import com.ecommerce.order.domain.RefundTypes.FinalizationStatus;
import com.ecommerce.order.domain.RefundTypes.ObligationType;
import com.ecommerce.order.store.CheckpointStore;
import com.ecommerce.order.store.CheckpointStore.CheckpointRow;
import com.ecommerce.order.store.OperationStore;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.RefundStore;
import com.ecommerce.order.store.RefundStore.CaseRow;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.UUID;

/**
 * Durable finalization of the existing Inventory Refund Scope (ADR-013, WF-ORDER-008a).
 * The intent (operation, reason, generation, command ID, canonical body) is frozen in the
 * same transaction as the financial result or no-payout decision, together with the
 * dispatch of {@code order-refund-scope-finalization-{order_id}-{generation}}. Only a
 * matching accepted receipt completes it; it never calls Payment.
 */
@Service
public class ScopeFinalization {

    private static final Logger log = LoggerFactory.getLogger(ScopeFinalization.class);
    static final String LANE = "REFUND";

    private final RefundStore refunds;
    private final OrderStore orders;
    private final OperationStore operations;
    private final CheckpointStore checkpoints;
    private final RemoteCommands remote;
    private final Gateways.Inventory inventory;
    private final ReconciliationLedger reconciliation;
    private final WorkflowEnqueuer enqueuer;
    private final ProblemFactory problems;
    private final TransactionTemplate tx;
    private final Json json;
    private final Clock clock;
    private final MeterRegistry meters;

    public ScopeFinalization(RefundStore refunds, OrderStore orders, OperationStore operations,
                             CheckpointStore checkpoints, RemoteCommands remote, Gateways.Inventory inventory,
                             ReconciliationLedger reconciliation, WorkflowEnqueuer enqueuer, ProblemFactory problems,
                             TransactionTemplate tx, Json json, Clock clock, MeterRegistry meters) {
        this.refunds = refunds;
        this.orders = orders;
        this.operations = operations;
        this.checkpoints = checkpoints;
        this.remote = remote;
        this.inventory = inventory;
        this.reconciliation = reconciliation;
        this.enqueuer = enqueuer;
        this.problems = problems;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Freezes a new finalization generation; caller holds the order and case locks and
     * has already verified that no unknown/accepted command would be superseded.
     */
    public int freezeIntent(CaseRow refundCase, FinalizationOperation operation, String reason, UUID decisionId) {
        int generation = refundCase.finalizationGeneration() + 1;
        long orderId = refundCase.orderId();
        String commandId = "order-" + orderId + "-refund-scope-" + operation.name().toLowerCase().replace('_', '-')
                + "-g" + generation;
        ObjectNode body = json.object();
        if (operation != FinalizationOperation.CANCEL) {
            body.put("reason", reason);
        }
        checkpoints.freeze(key(orderId, refundCase.refundScopeId(), operation, generation), commandId,
                json.write(body), CanonicalJson.digest(body));
        Map<String, Object> changes = new HashMap<>();
        changes.put("inventory_scope_finalization_status", FinalizationStatus.PENDING);
        changes.put("inventory_scope_finalization_generation", generation);
        changes.put("inventory_scope_finalization_operation", operation.name());
        changes.put("inventory_scope_finalization_reason", reason);
        changes.put("inventory_scope_finalization_command_id", commandId);
        changes.put("inventory_scope_finalization_decision_id", decisionId);
        refunds.update(orderId, changes);
        enqueuer.dispatch(orderId, enqueuer.finalization(orderId, generation));
        return generation;
    }

    /** COMPLETED, CONFLICT, UNKNOWN or SUPERSEDED. */
    public String execute(long orderId, int generation) {
        CaseRow refundCase = refunds.find(orderId).orElseThrow();
        if (refundCase.finalizationGeneration() != generation) {
            return "SUPERSEDED";
        }
        switch (refundCase.finalizationStatus()) {
            case COMPLETED:
                return "COMPLETED";
            case CONFLICT:
                return "CONFLICT";
            case NOT_REQUIRED:
            case NOT_REQUESTED:
                return "SUPERSEDED";
            default:
                break;
        }
        FinalizationOperation operation = FinalizationOperation.valueOf(refundCase.finalizationOperation());
        CheckpointStore.Key key = key(orderId, refundCase.refundScopeId(), operation, generation);
        CheckpointRow frozen = checkpoints.find(key).orElseThrow();
        String scopeId = refundCase.refundScopeId();
        Gateways.Command command = new Gateways.Command(frozen.commandId(),
                "order-refund-scope-finalization-" + orderId + "-" + generation, frozen.commandId());
        RemoteResult result = remote.execute(key, frozen.commandId(), json.read(frozen.requestJson()),
                cp -> switch (operation) {
                    case CLOSE -> inventory.closeRefundScope(scopeId, command, json.read(cp.requestJson()));
                    case CLOSE_INCOMPLETE -> inventory.closeIncompleteRefundScope(scopeId, command,
                            json.read(cp.requestJson()));
                    case CANCEL -> inventory.cancelRefundScope(scopeId, command);
                },
                r -> receiptMatches(r.body(), refundCase, operation) ? r
                        : RemoteResult.rejected(r.httpStatus(), "RECEIPT_MISMATCH", r.body()),
                r -> {
                    if (r.isAccepted()) {
                        recordReceipt(orderId, generation, r.body());
                    } else {
                        recordConflict(orderId, generation, r.errorCode(), frozen.commandId());
                    }
                });
        return switch (result.kind()) {
            case ACCEPTED -> "COMPLETED";
            case REJECTED -> "CONFLICT";
            case UNKNOWN -> "UNKNOWN";
        };
    }

    private boolean receiptMatches(JsonNode body, CaseRow refundCase, FinalizationOperation operation) {
        return body != null && operation.expectedOutcome().equals(body.path("outcome").asText())
                && refundCase.refundScopeId().equals(body.path("refund_scope_id").asText())
                && Ids.wire(refundCase.orderId()).equals(body.path("order_id").asText())
                && refundCase.refundRequestId().equals(body.path("refund_request_id").asText())
                && body.path("scope_revision").asLong(0) >= 1;
    }

    private void recordReceipt(long orderId, int generation, JsonNode body) {
        orders.lock(orderId);
        CaseRow refundCase = refunds.lock(orderId).orElseThrow();
        if (refundCase.finalizationGeneration() != generation
                || refundCase.finalizationStatus() == FinalizationStatus.COMPLETED) {
            return;
        }
        Instant now = clock.instant();
        Map<String, Object> changes = new HashMap<>();
        changes.put("inventory_scope_finalization_status", FinalizationStatus.COMPLETED);
        changes.put("inventory_scope_terminal_state", body.path("outcome").asText());
        changes.put("inventory_scope_finalization_response_revision", body.path("scope_revision").asLong());
        changes.put("inventory_scope_finalization_response_hash", CanonicalJson.digest(body));
        changes.put("inventory_scope_finalized_at", now);
        boolean noPayout = !"CLOSE".equals(refundCase.finalizationOperation());
        if (noPayout) {
            changes.put("case_status", CaseStatus.CLOSED_INCOMPLETE);
            changes.put("resolved_at", now);
        }
        refunds.update(orderId, changes);
        reconciliation.closeActive(orderId, ObligationType.REFUND_SCOPE_FINALIZATION,
                refundCase.finalizationCommandId(), "RESUME_FORWARD_REPAIR");
        if (noPayout) {
            reconciliation.closeActive(orderId, ObligationType.RETURN_MANUAL_VERIFICATION,
                    refundCase.refundRequestId(), "CLOSE_RETURN_WITHOUT_REFUND");
        }
        if (refundCase.operationId() != null) {
            CaseStatus financial = noPayout ? CaseStatus.CLOSED_INCOMPLETE : refundCase.status();
            OperationTypes.Status status = switch (financial) {
                case SUCCEEDED, EXTERNALLY_REMEDIATED, CLOSED_INCOMPLETE -> OperationTypes.Status.SUCCEEDED;
                default -> OperationTypes.Status.MANUAL_REVIEW_REQUIRED;
            };
            operations.transition(refundCase.operationId(), status, "/api/v1/orders/" + orderId + "/refund-case", null);
        }
        meters.counter("order.refund.scope_finalized", "outcome", body.path("outcome").asText()).increment();
    }

    private void recordConflict(long orderId, int generation, String code, String commandId) {
        orders.lock(orderId);
        CaseRow refundCase = refunds.lock(orderId).orElseThrow();
        if (refundCase.finalizationGeneration() != generation) {
            return;
        }
        refunds.update(orderId, Map.of("inventory_scope_finalization_status", FinalizationStatus.CONFLICT));
        reconciliation.open(orderId, ObligationType.REFUND_SCOPE_FINALIZATION, commandId, "CONFLICT:" + code,
                List.of(new ReconciliationLedger.Evidence("INVENTORY_COMMAND", "command/" + commandId,
                        CanonicalJson.sha256Hex(commandId + ":" + code), null, null)));
        manualReview(refundCase, "Inventory rejected the frozen scope finalization command");
        log.error("Scope finalization conflict order={} generation={} code={}", orderId, generation, code);
    }

    public void markRecovery(long orderId, int generation) {
        tx.executeWithoutResult(s -> {
            orders.lock(orderId);
            CaseRow refundCase = refunds.lock(orderId).orElseThrow();
            if (refundCase.finalizationGeneration() != generation
                    || refundCase.finalizationStatus() != FinalizationStatus.PENDING) {
                return;
            }
            refunds.update(orderId, Map.of("inventory_scope_finalization_status", FinalizationStatus.RECOVERY_REQUIRED));
            reconciliation.open(orderId, ObligationType.REFUND_SCOPE_FINALIZATION,
                    refundCase.finalizationCommandId(), "RECOVERY_REQUIRED", List.of());
            manualReview(refundCase, "Scope finalization retries exhausted; recovery continues");
            meters.counter("order.refund.scope_finalization_recovery").increment();
        });
    }

    private void manualReview(CaseRow refundCase, String detail) {
        if (refundCase.operationId() != null) {
            operations.transition(refundCase.operationId(), OperationTypes.Status.MANUAL_REVIEW_REQUIRED, null,
                    json.write(problems.operationProblem("INVALID_ORDER_TRANSITION", detail)));
        }
    }

    static CheckpointStore.Key key(long orderId, String scopeId, FinalizationOperation operation, int generation) {
        return new CheckpointStore.Key(orderId, LANE, "SCOPE_FINALIZATION", operation.name(), scopeId, generation);
    }
}
