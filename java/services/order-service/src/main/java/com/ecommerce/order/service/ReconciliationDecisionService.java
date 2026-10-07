package com.ecommerce.order.service;

import com.ecommerce.order.client.Gateways;
import com.ecommerce.order.client.RemoteResult;
import com.ecommerce.order.common.ApiException;
import com.ecommerce.order.common.CanonicalJson;
import com.ecommerce.order.common.ErrorCode;
import com.ecommerce.order.common.Ids;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.domain.OperationTypes;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.domain.RefundTypes.CaseStatus;
import com.ecommerce.order.domain.RefundTypes.FinalizationOperation;
import com.ecommerce.order.domain.RefundTypes.FinalizationStatus;
import com.ecommerce.order.domain.RefundTypes.ObligationType;
import com.ecommerce.order.domain.RefundTypes.Resolution;
import com.ecommerce.order.store.CheckpointStore;
import com.ecommerce.order.store.OperationStore;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.OrderStore.OrderRow;
import com.ecommerce.order.store.ReconciliationStore;
import com.ecommerce.order.store.ReconciliationStore.ObligationRow;
import com.ecommerce.order.store.RefundStore;
import com.ecommerce.order.store.RefundStore.CaseRow;
import com.ecommerce.order.web.Caller;
import com.ecommerce.order.web.dto.ReconciliationDtos.DecisionRequest;
import com.ecommerce.order.web.dto.ReconciliationDtos.EvidenceReference;
import com.ecommerce.order.workflow.WorkflowMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WF-ORDER-010: Finance/Ops decisions are appended against exactly one typed obligation
 * under the locked envelope revision. Decisions never write remote state directly or
 * assert unproven money movement; resumes replay original command identities only.
 */
@Service
public class ReconciliationDecisionService {

    private static final Map<ObligationType, Set<Resolution>> PERMITTED = Map.of(
            ObligationType.POST_CAPTURE_INVENTORY_REPAIR, EnumSet.of(Resolution.RESUME_FORWARD_REPAIR,
                    Resolution.CLOSE_AS_MANUAL_FULFILLMENT, Resolution.ESCALATE_TO_ACCOUNTING),
            ObligationType.POST_CAPTURE_PROMOTION_REPAIR, EnumSet.of(Resolution.RESUME_FORWARD_REPAIR,
                    Resolution.CLOSE_AS_MANUAL_FULFILLMENT, Resolution.ESCALATE_TO_ACCOUNTING),
            ObligationType.CAPTURE_AFTER_TERMINAL_NON_CAPTURE, EnumSet.of(Resolution.ESCALATE_TO_ACCOUNTING,
                    Resolution.CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION),
            ObligationType.PAYMENT_DISPUTE, EnumSet.of(Resolution.ESCALATE_TO_ACCOUNTING,
                    Resolution.CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION),
            ObligationType.REFUND_REVERSAL, EnumSet.of(Resolution.ESCALATE_TO_ACCOUNTING,
                    Resolution.CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION),
            ObligationType.CUSTOMER_PAYOUT_REMEDIATION, EnumSet.of(Resolution.ESCALATE_TO_ACCOUNTING,
                    Resolution.CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION),
            ObligationType.REFUND_SCOPE_FINALIZATION, EnumSet.of(Resolution.RESUME_FORWARD_REPAIR,
                    Resolution.ESCALATE_TO_ACCOUNTING),
            ObligationType.RETURN_MANUAL_VERIFICATION, EnumSet.of(Resolution.RESUME_FORWARD_REPAIR,
                    Resolution.CLOSE_RETURN_WITHOUT_REFUND, Resolution.ESCALATE_TO_ACCOUNTING));

    private final ReconciliationStore store;
    private final ReconciliationLedger ledger;
    private final OrderStore orders;
    private final RefundStore refunds;
    private final OperationStore operations;
    private final CheckpointStore checkpoints;
    private final ScopeFinalization finalization;
    private final OrderConvergence convergence;
    private final WorkflowEnqueuer enqueuer;
    private final Gateways.Inventory inventory;
    private final Gateways.Payment payment;
    private final TransactionTemplate tx;
    private final Json json;

    public ReconciliationDecisionService(ReconciliationStore store, ReconciliationLedger ledger, OrderStore orders,
                                         RefundStore refunds, OperationStore operations, CheckpointStore checkpoints,
                                         ScopeFinalization finalization, OrderConvergence convergence,
                                         WorkflowEnqueuer enqueuer, Gateways.Inventory inventory,
                                         Gateways.Payment payment, TransactionTemplate tx, Json json) {
        this.store = store;
        this.ledger = ledger;
        this.orders = orders;
        this.refunds = refunds;
        this.operations = operations;
        this.checkpoints = checkpoints;
        this.finalization = finalization;
        this.convergence = convergence;
        this.enqueuer = enqueuer;
        this.inventory = inventory;
        this.payment = payment;
        this.tx = tx;
        this.json = json;
    }

    // ------------------------------------------------------------------ read

    public ObjectNode read(String orderIdValue) {
        Long orderId = Ids.parseCanonical(orderIdValue);
        ReconciliationStore.CaseRow envelope = orderId == null ? null : store.findCase(orderId).orElse(null);
        List<ObligationRow> obligations = envelope == null ? List.of() : store.obligations(orderId);
        if (envelope == null || obligations.isEmpty()) {
            throw ApiException.notFound();
        }
        ObjectNode body = json.object();
        body.put("case_id", envelope.caseId().toString());
        body.put("order_id", Ids.wire(orderId));
        body.put("revision", envelope.revision());
        body.put("case_status", envelope.status());
        ArrayNode array = body.putArray("obligations");
        for (ObligationRow obligation : obligations) {
            ObjectNode node = array.addObject();
            node.put("obligation_id", obligation.obligationId().toString());
            node.put("obligation_type", obligation.type().name());
            node.put("status", obligation.status());
            ArrayNode evidence = json.mapper().createArrayNode();
            store.evidence(obligation.obligationId()).stream().limit(100).forEach(e -> evidence.addObject()
                    .put("source", e.source()).put("reference", e.reference()).put("sha256", e.sha256()));
            node.put("evidence_digest", CanonicalJson.digest(evidence));
            node.set("evidence", evidence);
            ArrayNode permitted = node.putArray("permitted_resolutions");
            if (obligation.open()) {
                PERMITTED.get(obligation.type()).forEach(r -> permitted.add(r.name()));
            }
            node.put("opened_at", obligation.openedAt().toString());
            node.put("updated_at", obligation.updatedAt().toString());
        }
        body.put("opened_at", envelope.openedAt().toString());
        body.put("updated_at", envelope.updatedAt().toString());
        return body;
    }

    // ------------------------------------------------------------------ decide

    public ResponseEntity<Object> decide(String orderIdValue, String key, DecisionRequest request, Caller caller) {
        if (!request.decisionId().equalsIgnoreCase(key)) {
            throw ApiException.invalid("Idempotency-Key must equal decision_id");
        }
        Long orderId = Ids.parseCanonical(orderIdValue);
        if (orderId == null) {
            throw ApiException.notFound();
        }
        Resolution resolution = Resolution.valueOf(request.resolutionType());
        validateShape(request, resolution);
        UUID decisionId = UUID.fromString(request.decisionId());
        JsonNode requestTree = json.tree(request);
        ObjectNode envelope = json.object();
        envelope.put("order_id", orderIdValue);
        envelope.set("request", requestTree);
        String digest = json.digest(envelope);
        var existing = store.decision(decisionId);
        if (existing.isPresent()) {
            if (!existing.get().requestDigest().equals(digest)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "decision_id reused with a different body");
            }
            return accepted(json.read(existing.get().responseJson()));
        }
        // Remote evidence is gathered outside the transaction, then guards are rechecked under lock.
        JsonNode scopeQuery = resolution == Resolution.CLOSE_RETURN_WITHOUT_REFUND
                ? queryScope(request.returnClosure().refundScopeId()) : null;
        JsonNode paymentState = resolution == Resolution.CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION
                ? orders.find(orderId).map(o -> o.capturedTransactionId() == null ? null
                : queryPayment(o.capturedTransactionId(), orderId)).orElse(null) : null;
        ObjectNode response = tx.execute(s -> apply(orderId, decisionId, request, resolution, digest, requestTree,
                scopeQuery, paymentState, caller));
        return accepted(response);
    }

    private ObjectNode apply(long orderId, UUID decisionId, DecisionRequest request, Resolution resolution,
                             String digest, JsonNode requestTree, JsonNode scopeQuery, JsonNode paymentState,
                             Caller caller) {
        OrderRow order = orders.lock(orderId).orElseThrow(ApiException::notFound);
        ReconciliationStore.CaseRow envelope = store.lockCase(orderId).orElseThrow(ApiException::notFound);
        if (!envelope.caseId().toString().equalsIgnoreCase(request.caseId())) {
            throw ApiException.notFound();
        }
        if (envelope.revision() != request.expectedCaseRevision()) {
            throw new ApiException(ErrorCode.STALE_CASE_REVISION, "Expected case revision " + request.expectedCaseRevision()
                    + " but the case is at " + envelope.revision());
        }
        ObligationRow obligation = store.lockObligation(UUID.fromString(request.obligationId()))
                .filter(o -> o.caseId().equals(envelope.caseId()))
                .orElseThrow(ApiException::notFound);
        if (!obligation.open() || !PERMITTED.get(obligation.type()).contains(resolution)) {
            throw new ApiException(ErrorCode.RESOLUTION_NOT_PERMITTED, resolution + " is not permitted for "
                    + obligation.type() + " in status " + obligation.status());
        }
        verifyEvidence(orderId, obligation, request.evidence());
        String obligationStatus = switch (resolution) {
            case RESUME_FORWARD_REPAIR -> resume(order, obligation, decisionId);
            case CLOSE_AS_MANUAL_FULFILLMENT -> manualFulfillment(order, obligation);
            case ESCALATE_TO_ACCOUNTING -> {
                ledger.setStatus(orderId, obligation.obligationId(), "EVIDENCE_PENDING", resolution.name(),
                        request.accountingCaseReference());
                yield "EVIDENCE_PENDING";
            }
            case CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION -> externalRemediation(order, obligation, request, paymentState);
            case CLOSE_RETURN_WITHOUT_REFUND -> closeReturnWithoutRefund(order, obligation, request, decisionId,
                    scopeQuery);
        };
        for (EvidenceReference e : request.evidence()) {
            store.addEvidence(obligation.obligationId(), e.source(), e.reference(), e.sha256(), null, null);
        }
        ReconciliationStore.CaseRow after = store.findCase(orderId).orElseThrow();
        ObjectNode response = json.object();
        response.put("case_id", after.caseId().toString());
        response.put("obligation_id", obligation.obligationId().toString());
        response.put("decision_id", decisionId.toString());
        response.put("obligation_status", obligationStatus);
        response.put("case_status", after.status());
        response.put("resolution_type", resolution.name());
        store.insertDecision(decisionId, obligation.obligationId(), envelope.caseId(), orderId,
                request.expectedCaseRevision(), resolution.name(), request.reasonCode(), digest, json.write(requestTree),
                caller.actorId(), caller.principal(), request.accountingCaseReference(), json.write(response));
        return response;
    }

    // ------------------------------------------------------------------ resolutions

    private String resume(OrderRow order, ObligationRow obligation, UUID decisionId) {
        long orderId = order.id();
        switch (obligation.type()) {
            case POST_CAPTURE_INVENTORY_REPAIR, POST_CAPTURE_PROMOTION_REPAIR -> {
                ledger.setStatus(orderId, obligation.obligationId(), "DECIDED", Resolution.RESUME_FORWARD_REPAIR.name(), null);
                enqueuer.message(orderId, enqueuer.reconciliation(orderId), "decision:" + decisionId,
                        WorkflowMessage.RESUME_POST_CAPTURE, Map.of("obligation_id", obligation.obligationId().toString()));
                return "DECIDED";
            }
            case REFUND_SCOPE_FINALIZATION -> {
                CaseRow refundCase = refunds.lock(orderId).orElseThrow();
                if (refundCase.finalizationStatus() == FinalizationStatus.RECOVERY_REQUIRED) {
                    refunds.update(orderId, Map.of("inventory_scope_finalization_status", FinalizationStatus.PENDING));
                    enqueuer.dispatch(orderId, enqueuer.finalization(orderId, refundCase.finalizationGeneration()));
                } else if (refundCase.finalizationStatus() == FinalizationStatus.CONFLICT && provenRejected(refundCase)) {
                    finalization.freezeIntent(refundCase, FinalizationOperation.valueOf(refundCase.finalizationOperation()),
                            refundCase.finalizationReason(), refundCase.finalizationDecisionId());
                } else {
                    throw new ApiException(ErrorCode.RESOLUTION_NOT_PERMITTED,
                            "Finalization is " + refundCase.finalizationStatus()
                                    + "; only RECOVERY_REQUIRED or a proven-rejected CONFLICT can resume");
                }
                ledger.setStatus(orderId, obligation.obligationId(), "DECIDED", Resolution.RESUME_FORWARD_REPAIR.name(), null);
                return "DECIDED";
            }
            case RETURN_MANUAL_VERIFICATION -> {
                CaseRow refundCase = refunds.lock(orderId).orElseThrow();
                if (refundCase.inspectionEventId() == null) {
                    throw new ApiException(ErrorCode.RECONCILIATION_EVIDENCE_INVALID,
                            "Resume requires full Inventory inspection completion evidence");
                }
                boolean noPayoutIntent = "CLOSE_INCOMPLETE".equals(refundCase.finalizationOperation())
                        || "CANCEL".equals(refundCase.finalizationOperation());
                if (noPayoutIntent && refundCase.finalizationStatus() != FinalizationStatus.NOT_REQUESTED) {
                    if (refundCase.finalizationStatus() != FinalizationStatus.CONFLICT || !provenRejected(refundCase)) {
                        throw new ApiException(ErrorCode.RESOLUTION_NOT_PERMITTED,
                                "An accepted or unknown no-payout command cannot be superseded");
                    }
                    Map<String, Object> clear = new HashMap<>();
                    clear.put("inventory_scope_finalization_status", FinalizationStatus.NOT_REQUESTED);
                    clear.put("inventory_scope_finalization_operation", null);
                    clear.put("inventory_scope_finalization_reason", null);
                    clear.put("inventory_scope_finalization_command_id", null);
                    clear.put("inventory_scope_finalization_decision_id", null);
                    refunds.update(orderId, clear);
                    ledger.closeActive(orderId, ObligationType.REFUND_SCOPE_FINALIZATION,
                            refundCase.finalizationCommandId(), Resolution.RESUME_FORWARD_REPAIR.name());
                }
                if (refundCase.status() != CaseStatus.MANUAL_VERIFICATION_REQUIRED || refundCase.refundCommandId() != null) {
                    throw new ApiException(ErrorCode.RESOLUTION_NOT_PERMITTED,
                            "Only a manual Buyer case without a Payment refund command can resume its gate");
                }
                refunds.update(orderId, Map.of("case_status", CaseStatus.WAITING_INVENTORY_SCOPE_INSPECTION));
                enqueuer.message(orderId, enqueuer.refundTarget(orderId, refundCase.workflowId()),
                        "decision:" + decisionId, WorkflowMessage.RESUME_REFUND, Map.of("decision_id", decisionId.toString()));
                ledger.close(orderId, obligation.obligationId(), Resolution.RESUME_FORWARD_REPAIR.name(), null);
                return "CLOSED";
            }
            default -> throw new ApiException(ErrorCode.RESOLUTION_NOT_PERMITTED, "Nothing to resume");
        }
    }

    private boolean provenRejected(CaseRow refundCase) {
        return checkpoints.findByCommand(refundCase.finalizationCommandId())
                .map(c -> c.rejected() && !"RECEIPT_MISMATCH".equals(c.errorCode())).orElse(false);
    }

    private String manualFulfillment(OrderRow order, ObligationRow obligation) {
        long orderId = order.id();
        ledger.close(orderId, obligation.obligationId(), Resolution.CLOSE_AS_MANUAL_FULFILLMENT.name(), null);
        if (!ledger.hasOpenPostCaptureRepair(orderId) && order.status() == OrderStatus.FINANCIAL_RECONCILIATION_REQUIRED) {
            orders.update(orderId, Map.of("status", OrderStatus.PROCESSING, "saga_phase", "FULFILLMENT"));
            convergence.tryComplete(orderId);
        }
        return "CLOSED";
    }

    /** ADR-018/019: closes a financial anomaly; payout/reversal converge the refund lane to REFUNDED. */
    private String externalRemediation(OrderRow order, ObligationRow obligation, DecisionRequest request,
                                       JsonNode paymentState) {
        long orderId = order.id();
        boolean settlementEvidence = request.evidence().stream().anyMatch(e -> "PAYMENT_RECONCILIATION".equals(e.source()));
        if (!settlementEvidence) {
            throw new ApiException(ErrorCode.RECONCILIATION_EVIDENCE_INVALID,
                    "External remediation requires immutable Payment/provider settlement evidence");
        }
        if (obligation.type() == ObligationType.CUSTOMER_PAYOUT_REMEDIATION
                || obligation.type() == ObligationType.REFUND_REVERSAL) {
            CaseRow refundCase = refunds.lock(orderId).orElseThrow(() -> new ApiException(
                    ErrorCode.RESOLUTION_NOT_PERMITTED, "No lifetime refund case"));
            var settlement = request.externalSettlement();
            if (settlement == null) {
                throw new ApiException(ErrorCode.RECONCILIATION_EVIDENCE_INVALID,
                        "external_settlement is required for a customer payout remediation");
            }
            Instant settledAt;
            try {
                settledAt = Instant.parse(settlement.settledAt());
            } catch (DateTimeParseException e) {
                throw ApiException.invalid("external_settlement.settled_at must be an RFC 3339 instant");
            }
            if (settlement.amount().amountMinor() != refundCase.amountLockedMinor()
                    || !order.userId().toString().equalsIgnoreCase(settlement.customerUserId())) {
                throw new ApiException(ErrorCode.RECONCILIATION_EVIDENCE_INVALID,
                        "Settlement must pay the order's customer exactly the locked VND amount");
            }
            if (refundCase.status() != CaseStatus.FAILED && refundCase.status() != CaseStatus.MANUAL_VERIFICATION_REQUIRED) {
                throw new ApiException(ErrorCode.RESOLUTION_NOT_PERMITTED, "Refund case is " + refundCase.status());
            }
            String providerRefund = paymentState == null ? null : paymentState.path("refund").path("status").asText(null);
            if ("SUCCESS".equals(providerRefund)) {
                throw new ApiException(ErrorCode.STALE_CASE_REVISION,
                        "Payment reports the original refund succeeded; external settlement rejected");
            }
            refunds.update(orderId, Map.of("case_status", CaseStatus.EXTERNALLY_REMEDIATED, "resolved_at", Instant.now()));
            if (order.status() == OrderStatus.RETURN_REQUESTED) {
                orders.update(orderId, Map.of("status", OrderStatus.REFUNDED, "refunded_at", settledAt));
            }
            if (refundCase.operationId() != null) {
                operations.transition(refundCase.operationId(), OperationTypes.Status.SUCCEEDED,
                        "/api/v1/orders/" + orderId + "/refund-case", null);
            }
            if (paymentState != null) {
                store.addEvidence(obligation.obligationId(), "PAYMENT_RECONCILIATION",
                        "payment-state/" + order.capturedTransactionId() + "/v"
                                + paymentState.path("financial_state_version").asLong(),
                        CanonicalJson.digest(paymentState), paymentState.path("financial_state_version").asLong(), null);
            }
        }
        ledger.close(orderId, obligation.obligationId(), Resolution.CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION.name(),
                request.accountingCaseReference());
        return "CLOSED";
    }

    /** ADR-013: freezes CLOSE_INCOMPLETE or zero-receipt CANCEL; closes only on the receipt. */
    private String closeReturnWithoutRefund(OrderRow order, ObligationRow obligation, DecisionRequest request,
                                            UUID decisionId, JsonNode scope) {
        long orderId = order.id();
        CaseRow refundCase = refunds.lock(orderId).orElseThrow();
        var closure = request.returnClosure();
        if (!closure.refundRequestId().equals(refundCase.refundRequestId())
                || refundCase.refundScopeId() == null || !closure.refundScopeId().equals(refundCase.refundScopeId())) {
            throw new ApiException(ErrorCode.RECONCILIATION_EVIDENCE_INVALID, "return_closure does not match the case");
        }
        if (refundCase.status() != CaseStatus.MANUAL_VERIFICATION_REQUIRED || refundCase.refundTransactionId() != null
                || refundCase.refundCommandId() != null) {
            throw new ApiException(ErrorCode.RESOLUTION_NOT_PERMITTED,
                    "No-payout closure needs a manual Buyer case without Payment initiation");
        }
        if (refundCase.inspectionEventId() != null) {
            throw new ApiException(ErrorCode.RESOLUTION_NOT_PERMITTED,
                    "Full inspection completion exists; resume the Payment gate instead");
        }
        boolean priorRejected = refundCase.finalizationStatus() == FinalizationStatus.CONFLICT && provenRejected(refundCase);
        if (refundCase.finalizationStatus() != FinalizationStatus.NOT_REQUESTED && !priorRejected) {
            throw new ApiException(ErrorCode.RESOLUTION_NOT_PERMITTED,
                    "A scope finalization intent is already " + refundCase.finalizationStatus());
        }
        if (scope == null || !closure.refundScopeId().equals(scope.path("refund_scope_id").asText())) {
            throw new ApiException(ErrorCode.RECONCILIATION_EVIDENCE_INVALID, "Inventory scope query does not match");
        }
        FinalizationOperation operation = noPayoutOperation(scope);
        if (operation == null) {
            throw new ApiException(ErrorCode.RECONCILIATION_EVIDENCE_INVALID,
                    "Scope must be REGISTERED with zero receipt, or IN_PROGRESS with every received unit final-inspected"
                            + " and expected units missing");
        }
        store.addEvidence(obligation.obligationId(), "INVENTORY_COMMAND",
                "refund-scope-query/order-" + orderId + "/" + closure.refundScopeId() + "/revision-"
                        + scope.path("revision").asLong(), CanonicalJson.digest(scope), scope.path("revision").asLong(),
                json.write(scope));
        finalization.freezeIntent(refundCase, operation, closure.reason(), decisionId);
        ledger.setStatus(orderId, obligation.obligationId(), "DECIDED", Resolution.CLOSE_RETURN_WITHOUT_REFUND.name(), null);
        enqueuer.message(orderId, enqueuer.refundTarget(orderId, refundCase.workflowId()), "decision:" + decisionId,
                WorkflowMessage.NO_PAYOUT_DECIDED, Map.of("decision_id", decisionId.toString()));
        return "DECIDED";
    }

    static FinalizationOperation noPayoutOperation(JsonNode scope) {
        String state = scope.path("state").asText();
        JsonNode lines = scope.path("lines");
        if ("REGISTERED".equals(state)) {
            for (JsonNode line : lines) {
                if (line.path("received_quantity").asLong() != 0) {
                    return null;
                }
            }
            return FinalizationOperation.CANCEL;
        }
        if ("IN_PROGRESS".equals(state)) {
            boolean missing = false;
            for (JsonNode line : lines) {
                long received = line.path("received_quantity").asLong();
                if (line.path("passed_quantity").asLong() + line.path("failed_quantity").asLong() != received) {
                    return null;
                }
                missing |= received < line.path("expected_quantity").asLong();
            }
            return missing ? FinalizationOperation.CLOSE_INCOMPLETE : null;
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private void validateShape(DecisionRequest request, Resolution resolution) {
        boolean closeReturn = resolution == Resolution.CLOSE_RETURN_WITHOUT_REFUND;
        if (closeReturn != (request.returnClosure() != null)) {
            throw ApiException.invalid("return_closure is required only for CLOSE_RETURN_WITHOUT_REFUND");
        }
        if (closeReturn && !request.returnClosure().reason().equals(request.reasonCode())) {
            throw ApiException.invalid("return_closure.reason must equal reason_code");
        }
        boolean accounting = resolution == Resolution.ESCALATE_TO_ACCOUNTING
                || resolution == Resolution.CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION;
        boolean hasReference = request.accountingCaseReference() != null && !request.accountingCaseReference().isBlank();
        if (accounting != hasReference) {
            throw ApiException.invalid("accounting_case_reference is required only for accounting resolutions");
        }
        if (request.externalSettlement() != null && resolution != Resolution.CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION) {
            throw ApiException.invalid("external_settlement is only allowed for external financial remediation");
        }
    }

    /** Durable references must exist in Order state; settlement records are appended as new evidence. */
    private void verifyEvidence(long orderId, ObligationRow obligation, List<EvidenceReference> evidence) {
        var known = store.evidence(obligation.obligationId());
        var orderCheckpoints = checkpoints.forOrder(orderId);
        for (EvidenceReference e : evidence) {
            if ("PAYMENT_RECONCILIATION".equals(e.source())) {
                continue;
            }
            boolean matchesObligation = known.stream().anyMatch(k -> k.source().equals(e.source())
                    && k.reference().equals(e.reference()) && k.sha256().equals(e.sha256()));
            boolean matchesCheckpoint = "ORDER_CHECKPOINT".equals(e.source()) && orderCheckpoints.stream()
                    .anyMatch(c -> e.reference().equals("checkpoint/" + c.commandId())
                            && (e.sha256().equals(c.requestDigest()) || e.sha256().equals(c.responseDigest())));
            // The scope query itself is re-run and checkpointed by Order under this decision.
            boolean scopeQuery = e.reference().startsWith("refund-scope-query/");
            if (!matchesObligation && !matchesCheckpoint && !scopeQuery) {
                throw new ApiException(ErrorCode.RECONCILIATION_EVIDENCE_INVALID,
                        "Evidence " + e.reference() + " is not a persisted Order/Inventory/Promotion record");
            }
        }
    }

    private JsonNode queryScope(String scopeId) {
        RemoteResult result = inventory.getRefundScope(scopeId);
        if (result.isUnknown()) {
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Inventory scope query unavailable", List.of(), 5,
                    Map.of());
        }
        return result.isAccepted() ? result.body() : null;
    }

    private JsonNode queryPayment(String transactionId, long orderId) {
        RemoteResult result = payment.getPayment(transactionId, "order-financial-reconciliation-" + orderId);
        if (!result.isAccepted()) {
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Payment state query unavailable", List.of(), 5,
                    Map.of());
        }
        return result.body();
    }

    private static ResponseEntity<Object> accepted(JsonNode body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
