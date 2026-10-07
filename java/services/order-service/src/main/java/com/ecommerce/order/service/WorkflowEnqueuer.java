package com.ecommerce.order.service;

import com.ecommerce.order.common.Ids;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.store.WorkflowOutboxStore;
import com.ecommerce.order.workflow.WorkflowNames;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes workflow start/message intents inside the caller's local transaction (ADR-015).
 * Nothing here calls Temporal: the dispatcher delivers after commit.
 */
@Component
public class WorkflowEnqueuer {

    /** Typed start inputs, serialized as the workflow argument. */
    public record Start(String workflowId, String workflowType, String taskQueue, Map<String, String> input) {
    }

    private final WorkflowOutboxStore store;
    private final Json json;
    private final OrderProperties.Temporal temporal;
    private final DispatchNudger nudger;

    public WorkflowEnqueuer(WorkflowOutboxStore store, Json json, OrderProperties properties, DispatchNudger nudger) {
        this.store = store;
        this.json = json;
        this.temporal = properties.temporal();
        this.nudger = nudger;
    }

    public Start checkout(String checkoutToken) {
        return new Start(WorkflowNames.checkout(checkoutToken), WorkflowNames.CHECKOUT_TYPE, temporal.checkoutQueue(),
                Map.of("checkout_token", checkoutToken));
    }

    public Start buyerRefund(long orderId) {
        return new Start(WorkflowNames.buyerRefund(orderId), WorkflowNames.REFUND_TYPE, temporal.refundQueue(),
                Map.of("order_id", Ids.wire(orderId), "mode", "BUYER"));
    }

    public Start adminRefund(long orderId, UUID attemptId) {
        return new Start(WorkflowNames.adminRefund(attemptId), WorkflowNames.REFUND_TYPE, temporal.refundQueue(),
                Map.of("order_id", Ids.wire(orderId), "mode", "ADMIN", "attempt_id", attemptId.toString()));
    }

    public Start finalization(long orderId, int generation) {
        return new Start(WorkflowNames.finalization(orderId, generation), WorkflowNames.FINALIZATION_TYPE,
                temporal.refundQueue(), Map.of("order_id", Ids.wire(orderId), "generation", Integer.toString(generation)));
    }

    public Start reconciliation(long orderId) {
        return new Start(WorkflowNames.reconciliation(orderId), WorkflowNames.RECONCILIATION_TYPE,
                temporal.reconciliationQueue(), Map.of("order_id", Ids.wire(orderId)));
    }

    /** Refund messages target the case's own workflow (Buyer or Admin attempt). */
    public Start refundTarget(long orderId, String caseWorkflowId) {
        if (caseWorkflowId.equals(WorkflowNames.buyerRefund(orderId))) {
            return buyerRefund(orderId);
        }
        String attempt = caseWorkflowId.substring("order-admin-refund-".length());
        return adminRefund(orderId, UUID.fromString(attempt));
    }

    public void dispatch(Long orderId, Start start) {
        String input = json.write(new LinkedHashMap<>(start.input()));
        store.insertDispatch(Ids.newUuid(), orderId, start.workflowId(), start.workflowType(), start.taskQueue(),
                input, json.digest(start.input()));
        nudger.nudgeAfterCommit();
    }

    public void message(Long orderId, Start target, String messageId, String type, Map<String, String> attributes) {
        Map<String, String> payload = new LinkedHashMap<>(attributes);
        store.insertMessage(messageId, orderId, target.workflowId(), target.workflowType(), target.taskQueue(),
                json.write(new LinkedHashMap<>(target.input())), type, json.write(payload), json.digest(payload));
        nudger.nudgeAfterCommit();
    }

    /** Stable inbound message identity: source + event + target + type (ADR-015). */
    public static String factMessageId(String source, String eventId, String targetWorkflowId, String type) {
        return source + ":" + eventId + ":" + targetWorkflowId + ":" + type;
    }
}
