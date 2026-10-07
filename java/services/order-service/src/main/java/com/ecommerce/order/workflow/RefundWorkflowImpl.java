package com.ecommerce.order.workflow;

import com.ecommerce.order.workflow.ActivityResults.Outcome;
import com.ecommerce.order.workflow.ActivityResults.RefundState;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * OrderRefundWorkflow (WF-ORDER-008). Buyer: {@code order-refund-{order_id}} registers the
 * selected scope, waits up to 30 days for full inspection completion, then calls Payment
 * once. Admin: {@code order-admin-refund-{attempt_id}} first needs Inventory's atomic
 * all-line release. Scope closure is dispatched durably with the financial result.
 */
public class RefundWorkflowImpl extends MessageQueueWorkflow implements OrderWorkflows.Refund {

    private static final Duration MANUAL_IDLE = Duration.ofDays(7);

    private long orderId;
    private String phase;
    private long inspectionDeadline;

    @Override
    public void onMessage(WorkflowMessage message) {
        accept(message);
    }

    @Override
    public void run(WorkflowInput input) {
        restore(input);
        orderId = Long.parseLong(input.arg("order_id"));
        String mode = input.arg("mode");
        String attemptId = input.arg("attempt_id");
        RefundState loaded = durable(() -> activities.loadRefund(orderId, mode, attemptId));
        phase = loaded.phase();
        inspectionDeadline = loaded.inspectionDeadlineEpochMillis();
        while (!"DONE".equals(phase)) {
            switch (phase) {
                case "RELEASE" -> release(attemptId);
                case "REGISTER" -> register();
                case "WAIT_INSPECTION" -> waitInspection();
                case "MANUAL" -> manual();
                case "PAY" -> pay();
                case "WAIT_RESULT" -> waitResult();
                default -> phase = "DONE";
            }
            if (continueAsNewSuggested() && !"DONE".equals(phase)) {
                Workflow.continueAsNew(carry(input.args()));
            }
        }
    }

    private void release(String attemptId) {
        int attempt = 0;
        while (true) {
            Outcome outcome = durable(() -> activities.releaseBeforeDispatch(orderId, attemptId));
            if (outcome.isAccepted()) {
                phase = "PAY";
                return;
            }
            if (outcome.isRejected()) {
                phase = "DONE";
                return;
            }
            attempt++;
            if (attempt == 3) {
                durableVoid(() -> activities.markAdminAttemptRecovery(attemptId));
            }
            Workflow.sleep(backoff(attempt));
        }
    }

    private void register() {
        Outcome outcome = untilKnown(() -> activities.registerRefundScope(orderId));
        phase = outcome.isAccepted() ? "WAIT_INSPECTION" : "MANUAL";
    }

    private void waitInspection() {
        long remaining = inspectionDeadline - Workflow.currentTimeMillis();
        if (queue.isEmpty() && remaining > 0) {
            Workflow.await(Duration.ofMillis(Math.min(remaining, Duration.ofDays(1).toMillis())), () -> !queue.isEmpty());
        }
        if (!queue.isEmpty()) {
            WorkflowMessage message = queue.poll();
            if (WorkflowMessage.INSPECTION_COMPLETED.equals(message.type())) {
                String gate = durable(() -> activities.checkRefundGate(orderId));
                if ("READY".equals(gate) || "INITIATED".equals(gate)) {
                    phase = "PAY";
                }
            } else if (WorkflowMessage.NO_PAYOUT_DECIDED.equals(message.type())) {
                phase = "DONE";
            }
            return;
        }
        if (Workflow.currentTimeMillis() >= inspectionDeadline) {
            // No automatic closure or Payment bypass at 30 days (ADR-013).
            durableVoid(() -> activities.enterManualVerification(orderId, "INSPECTION_TIMEOUT"));
            phase = "MANUAL";
        }
    }

    private void manual() {
        if (queue.isEmpty()) {
            boolean received = Workflow.await(MANUAL_IDLE, () -> !queue.isEmpty());
            if (!received) {
                return;
            }
        }
        WorkflowMessage message = queue.poll();
        switch (message.type()) {
            case WorkflowMessage.RESUME_REFUND -> {
                String gate = durable(() -> activities.checkRefundGate(orderId));
                if ("READY".equals(gate) || "INITIATED".equals(gate)) {
                    phase = "PAY";
                }
            }
            case WorkflowMessage.NO_PAYOUT_DECIDED -> phase = "DONE";
            default -> log.info("Manual lane retains {} as evidence", message.type());
        }
    }

    private void pay() {
        String gate = durable(() -> activities.checkRefundGate(orderId));
        if (!"READY".equals(gate) && !"INITIATED".equals(gate)) {
            phase = "MANUAL";
            return;
        }
        Outcome outcome = untilKnown(() -> activities.initiateRefund(orderId));
        phase = outcome.isAccepted() ? "WAIT_RESULT" : "DONE";
    }

    private void waitResult() {
        if (queue.isEmpty()) {
            Workflow.await(Duration.ofDays(1), () -> !queue.isEmpty());
            return;
        }
        WorkflowMessage message = queue.poll();
        if (WorkflowMessage.REFUND_RESULT.equals(message.type())) {
            String result = durable(() -> activities.applyRefundResult(orderId, message.attributes()));
            if (!"PROCESSING".equals(result) && !"IGNORED".equals(result)) {
                phase = "DONE";
            }
        }
    }
}
