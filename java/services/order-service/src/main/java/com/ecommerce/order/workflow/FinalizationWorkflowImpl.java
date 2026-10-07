package com.ecommerce.order.workflow;

import io.temporal.workflow.Workflow;

/**
 * OrderRefundScopeFinalizationWorkflow {@code order-refund-scope-finalization-{order_id}-{generation}}
 * (WF-ORDER-008a): replays only the frozen Inventory close/close-incomplete/cancel command
 * for one generation. After three unknown attempts it records RECOVERY_REQUIRED and keeps
 * retrying the same command; it never calls Payment.
 */
public class FinalizationWorkflowImpl extends MessageQueueWorkflow implements OrderWorkflows.Finalization {

    @Override
    public void onMessage(WorkflowMessage message) {
        accept(message);
    }

    @Override
    public void run(WorkflowInput input) {
        restore(input);
        long orderId = Long.parseLong(input.arg("order_id"));
        int generation = Integer.parseInt(input.arg("generation"));
        int attempt = Integer.parseInt(state.getOrDefault("attempt", "0"));
        while (true) {
            String result = durable(() -> activities.executeFinalization(orderId, generation));
            if (!"UNKNOWN".equals(result)) {
                return;
            }
            attempt++;
            if (attempt == 3) {
                durableVoid(() -> activities.markFinalizationRecovery(orderId, generation));
            }
            Workflow.sleep(backoff(attempt));
            if (continueAsNewSuggested()) {
                state.put("attempt", Integer.toString(attempt));
                Workflow.continueAsNew(carry(input.args()));
            }
        }
    }
}
