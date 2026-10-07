package com.ecommerce.order.messaging;

import com.ecommerce.order.common.Ids;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.domain.RefundTypes.CaseStatus;
import com.ecommerce.order.domain.RefundTypes.FinalizationStatus;
import com.ecommerce.order.domain.RefundTypes.ObligationType;
import com.ecommerce.order.service.ReconciliationLedger;
import com.ecommerce.order.service.WorkflowEnqueuer;
import com.ecommerce.order.store.InboxStore;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.RefundStore;
import com.ecommerce.order.store.RefundStore.CaseLineRow;
import com.ecommerce.order.store.RefundStore.CaseRow;
import com.ecommerce.order.store.ReconciliationStore;
import com.ecommerce.order.workflow.WorkflowMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * order-inventory-return-consumer (WF-ORDER-008): validates the selected-scope
 * inspection completion against the immutable case lines, persists the complete
 * evidence and its workflow message atomically. Completion during a pending no-payout
 * decision or after a terminal no-payout case is retained as evidence only (ADR-013).
 */
@Component
public class InspectionIntake {

    public static final String TYPE = "com.ecommerce.inventory.refund.all-items-inspection-completed.v1";

    private static final Logger log = LoggerFactory.getLogger(InspectionIntake.class);

    private final InboxStore inbox;
    private final OrderStore orders;
    private final RefundStore refunds;
    private final ReconciliationStore reconciliationStore;
    private final ReconciliationLedger reconciliation;
    private final WorkflowEnqueuer enqueuer;
    private final TransactionTemplate tx;
    private final Json json;
    private final MeterRegistry meters;

    public InspectionIntake(InboxStore inbox, OrderStore orders, RefundStore refunds,
                            ReconciliationStore reconciliationStore, ReconciliationLedger reconciliation,
                            WorkflowEnqueuer enqueuer, TransactionTemplate tx, Json json, MeterRegistry meters) {
        this.inbox = inbox;
        this.orders = orders;
        this.refunds = refunds;
        this.reconciliationStore = reconciliationStore;
        this.reconciliation = reconciliation;
        this.enqueuer = enqueuer;
        this.tx = tx;
        this.json = json;
        this.meters = meters;
    }

    public void handle(String key, String value) {
        CloudEvent event = CloudEvent.parse(json.mapper(), value);
        if (event == null || !FulfillmentIntake.INVENTORY_SOURCE.equals(event.source()) || !TYPE.equals(event.type())) {
            return;
        }
        JsonNode data = event.data();
        Long orderId = data == null ? null : Ids.parseCanonical(data.path("order_id").asText(null));
        String scopeId = data == null ? "invalid" : data.path("refund_scope_id").asText("invalid");
        long revision = data == null ? 0 : data.path("scope_revision").asLong(0);
        String eventId = event.hasUuidId() ? event.id() : "invalid-" + event.sha256();
        tx.executeWithoutResult(s -> {
            boolean known = orderId != null && orders.lock(orderId).isPresent();
            String problem = !event.hasUuidId() || !event.dataIsObject() || revision < 1 ? "INVALID_ENVELOPE"
                    : !known ? "UNKNOWN_ORDER" : null;
            InboxStore.InsertResult inserted = inbox.insert(FulfillmentIntake.INVENTORY_SOURCE, eventId, event.type(),
                    known ? orderId : null, "refund-scope:" + scopeId, revision, PaymentIntake.digest(event),
                    json.write(event.raw()), problem == null ? "RECEIVED" : "REJECTED");
            if (inserted != InboxStore.InsertResult.INSERTED) {
                return;
            }
            String result = problem != null ? "REJECTED:" + problem : apply(orderId, event, eventId);
            inbox.setStatus(FulfillmentIntake.INVENTORY_SOURCE, eventId,
                    result.startsWith("REJECTED:") ? "REJECTED" : result,
                    result.startsWith("REJECTED:") ? result.substring(9) : null);
        });
    }

    private String apply(long orderId, CloudEvent event, String eventId) {
        JsonNode data = event.data();
        Optional<CaseRow> found = refunds.lock(orderId);
        if (found.isEmpty() || found.get().refundScopeId() == null
                || !found.get().refundScopeId().equals(data.path("refund_scope_id").asText())
                || !found.get().refundRequestId().equals(data.path("refund_request_id").asText())) {
            return "REJECTED:SCOPE_IDENTITY_MISMATCH";
        }
        CaseRow refundCase = found.get();
        String mismatch = compareLines(refunds.lines(orderId), data.path("lines"));
        if (mismatch != null) {
            log.error("Inspection completion mismatch order={} scope={}: {}", orderId, refundCase.refundScopeId(), mismatch);
            meters.counter("order.refund.inspection_mismatch").increment();
            return "REJECTED:" + mismatch;
        }
        if (refundCase.inspectionEventId() != null) {
            return "APPLIED";
        }
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("inventory_inspection_event_id", UUID.fromString(eventId));
        evidence.put("inventory_inspection_payload_hash", PaymentIntake.digest(event));
        evidence.put("inventory_scope_revision", data.path("scope_revision").asLong());
        evidence.put("inventory_inspection_lines", json.write(data.path("lines")));
        evidence.put("inventory_scope_inspection_completed_at", Instant.now());
        refunds.update(orderId, evidence);
        boolean noPayoutPending = refundCase.finalizationStatus() != FinalizationStatus.NOT_REQUESTED
                && ("CLOSE_INCOMPLETE".equals(refundCase.finalizationOperation())
                || "CANCEL".equals(refundCase.finalizationOperation()));
        if (noPayoutPending || refundCase.status() == CaseStatus.MANUAL_VERIFICATION_REQUIRED
                || refundCase.status().resolved()) {
            // Evidence only: a pending/terminal no-payout decision or the manual lane owns the case.
            reconciliationStore.activeObligation(orderId, ObligationType.RETURN_MANUAL_VERIFICATION,
                    refundCase.refundRequestId()).ifPresent(o -> reconciliation.attachEvidence(o.obligationId(),
                    orderId, new ReconciliationLedger.Evidence("INVENTORY_COMMAND",
                            "inspection-completed/" + eventId, PaymentIntake.digest(event),
                            data.path("scope_revision").asLong(), json.write(data))));
            return "APPLIED";
        }
        WorkflowEnqueuer.Start target = enqueuer.refundTarget(orderId, refundCase.workflowId());
        enqueuer.message(orderId, target, WorkflowEnqueuer.factMessageId(FulfillmentIntake.INVENTORY_SOURCE, eventId,
                        target.workflowId(), WorkflowMessage.INSPECTION_COMPLETED),
                WorkflowMessage.INSPECTION_COMPLETED, Map.of("event_id", eventId,
                        "scope_revision", data.path("scope_revision").asText()));
        return "ROUTED";
    }

    /** Every immutable case line must be present once with full expected = received = passed + failed. */
    static String compareLines(List<CaseLineRow> caseLines, JsonNode eventLines) {
        if (!eventLines.isArray() || eventLines.size() != caseLines.size()) {
            return "LINE_SET_MISMATCH";
        }
        Map<String, JsonNode> byLine = new HashMap<>();
        for (JsonNode line : (ArrayNode) eventLines) {
            if (byLine.put(line.path("order_line_id").asText(), line) != null) {
                return "DUPLICATE_LINE";
            }
        }
        for (CaseLineRow expected : caseLines) {
            JsonNode line = byLine.get(Long.toString(expected.orderLineId()));
            if (line == null) {
                return "LINE_SET_MISMATCH";
            }
            long exp = line.path("expected_quantity").asLong(-1);
            long received = line.path("received_quantity").asLong(-1);
            long passed = line.path("passed_quantity").asLong(-1);
            long failed = line.path("failed_quantity").asLong(-1);
            if (exp != expected.expectedQuantity() || received != exp || passed < 0 || failed < 0
                    || passed + failed != received) {
                return "COUNTER_MISMATCH";
            }
        }
        return null;
    }
}
