package com.ecommerce.order.workflow;

import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * OrderFinancialReconciliationWorkflow {@code order-financial-reconciliation-{order_id}}
 * (WF-ORDER-010): executes proof-backed resumes with the original command IDs and is the
 * named recovery owner for financial messages rerouted from a closed refund lane. It never
 * rolls business state back or mutates Payment/Promotion facts directly.
 */
public class ReconciliationWorkflowImpl extends MessageQueueWorkflow implements OrderWorkflows.Reconciliation {

    private static final Duration IDLE = Duration.ofMinutes(10);

    @Override
    public void onMessage(WorkflowMessage message) {
        accept(message);
    }

    @Override
    public void run(WorkflowInput input) {
        restore(input);
        long orderId = Long.parseLong(input.arg("order_id"));
        while (true) {
            if (queue.isEmpty() && !Workflow.await(IDLE, () -> !queue.isEmpty())) {
                return;
            }
            WorkflowMessage message = queue.poll();
            switch (message.type()) {
                case WorkflowMessage.RESUME_POST_CAPTURE -> {
                    int attempt = 0;
                    String result = durable(() -> activities.resumePostCaptureRepair(orderId));
                    while ("PENDING".equals(result) && attempt < 8) {
                        attempt++;
                        Workflow.sleep(backoff(attempt));
                        result = durable(() -> activities.resumePostCaptureRepair(orderId));
                    }
                    log.info("Post-capture resume for order {} ended {}", orderId, result);
                }
                case WorkflowMessage.REFUND_RESULT ->
                        durable(() -> activities.applyRefundResult(orderId, message.attributes()));
                default -> log.warn("Reconciliation workflow ignores {}", message.type());
            }
            if (continueAsNewSuggested()) {
                Workflow.continueAsNew(carry(input.args()));
            }
        }
    }
}
