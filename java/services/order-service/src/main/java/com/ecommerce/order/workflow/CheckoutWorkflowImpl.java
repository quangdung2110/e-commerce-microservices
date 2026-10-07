package com.ecommerce.order.workflow;

import com.ecommerce.order.workflow.ActivityResults.Applied;
import com.ecommerce.order.workflow.ActivityResults.CancelResult;
import com.ecommerce.order.workflow.ActivityResults.NextAttempt;
import com.ecommerce.order.workflow.ActivityResults.Outcome;
import com.ecommerce.order.workflow.ActivityResults.Prepared;
import com.ecommerce.order.workflow.ActivityResults.Ready;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * OrderCheckoutWorkflow {@code order-checkout-{checkout_token}} (WF-ORDER-004/005/009/011).
 * Phases: PREPARE -> AWAITING -> (CANCELLING | POST_PAYMENT) -> DONE. Payment success is
 * the financial watermark: before it the saga compensates (Payment non-capture first,
 * then independent Promotion/Inventory cleanup); after it only forward repair runs.
 */
public class CheckoutWorkflowImpl extends MessageQueueWorkflow implements OrderWorkflows.Checkout {

    private String token;
    private long orderId;
    private List<String> promotionIds = List.of();
    private long deadlineMillis;
    private String phase = "PREPARE";

    @Override
    public void onMessage(WorkflowMessage message) {
        accept(message);
    }

    @Override
    public void run(WorkflowInput input) {
        restore(input);
        token = input.arg("checkout_token");
        if (state.containsKey("phase")) {
            phase = state.get("phase");
            orderId = Long.parseLong(state.get("order_id"));
            deadlineMillis = Long.parseLong(state.get("deadline"));
            String promos = state.getOrDefault("promotions", "");
            promotionIds = promos.isEmpty() ? List.of() : Arrays.asList(promos.split(","));
        }
        while (true) {
            switch (phase) {
                case "PREPARE" -> prepare();
                case "AWAITING" -> awaitPayment();
                case "CANCELLING" -> cancel();
                case "POST_PAYMENT" -> postPayment();
                default -> {
                    drainTerminal();
                    return;
                }
            }
            if (continueAsNewSuggested() && !"DONE".equals(phase)) {
                state.put("phase", phase);
                state.put("order_id", Long.toString(orderId));
                state.put("deadline", Long.toString(deadlineMillis));
                state.put("promotions", String.join(",", promotionIds));
                Workflow.continueAsNew(carry(input.args()));
            }
        }
    }

    // ------------------------------------------------------------------ PREPARE

    private void prepare() {
        Prepared prepared = durable(() -> activities.prepareOrder(token));
        orderId = prepared.orderId();
        promotionIds = prepared.promotionIds();
        deadlineMillis = prepared.deadlineEpochMillis();
        switch (prepared.status()) {
            case "CANCELLING" -> {
                phase = prepared.deadlineLocked() ? "CANCELLING" : failAndCancel("ORDER_CANCELLED_DURING_PREPARATION", null, null);
                return;
            }
            case "CANCELLED" -> {
                phase = "DONE";
                return;
            }
            case "PENDING_PAYMENT" -> {
                if (prepared.deadlineLocked()) {
                    phase = "AWAITING";
                    return;
                }
            }
            default -> {
                phase = "POST_PAYMENT";
                return;
            }
        }
        Outcome inventory = untilKnown(() -> activities.reserveInventory(orderId));
        if (inventory.isRejected()) {
            phase = failAndCancel("INVENTORY_RESERVATION_REJECTED", null, null);
            return;
        }
        for (String promotionId : promotionIds) {
            Outcome promotion = untilKnown(() -> activities.reservePromotion(orderId, promotionId));
            if (promotion.isRejected()) {
                phase = failAndCancel("PROMOTION_RECALCULATION_NEEDED", promotionId, promotion.errorCode());
                return;
            }
        }
        Outcome payment = untilKnown(() -> activities.initiatePayment(orderId, 1, null, null));
        if (payment.isRejected()) {
            phase = failAndCancel("PAYMENT_INITIATION_REJECTED", null, null);
            return;
        }
        Ready ready = durable(() -> activities.markPaymentReady(orderId));
        switch (ready.state()) {
            case "READY" -> {
                deadlineMillis = ready.deadlineEpochMillis();
                phase = "AWAITING";
            }
            case "CANCELLING" -> phase = failAndCancel("ORDER_CANCELLED_DURING_PREPARATION", null, null);
            case "WINDOW_EXHAUSTED" -> phase = failAndCancel("CHECKOUT_PAYMENT_WINDOW_EXHAUSTED", null, null);
            default -> phase = "POST_PAYMENT";
        }
    }

    private String failAndCancel(String code, String promotionId, String reason) {
        durableVoid(() -> activities.failPreparation(orderId, code, promotionId, reason));
        return "CANCELLING";
    }

    // ------------------------------------------------------------------ AWAITING (payment window)

    private void awaitPayment() {
        while ("AWAITING".equals(phase)) {
            long remaining = deadlineMillis - Workflow.currentTimeMillis();
            if (queue.isEmpty() && remaining > 0) {
                Workflow.await(Duration.ofMillis(remaining), () -> !queue.isEmpty());
            }
            if (!queue.isEmpty()) {
                handleAwaiting(queue.poll());
            } else if (Workflow.currentTimeMillis() >= deadlineMillis) {
                Applied applied = durable(() -> activities.beginDeadlineCancellation(orderId));
                phase = switch (applied.action()) {
                    case Applied.CANCEL_STARTED -> "CANCELLING";
                    case Applied.CANCELLED -> "DONE";
                    default -> "POST_PAYMENT";
                };
            }
            if (continueAsNewSuggested()) {
                return;
            }
        }
    }

    private void handleAwaiting(WorkflowMessage message) {
        switch (message.type()) {
            case WorkflowMessage.PAYMENT_RESULT -> {
                Applied applied = durable(() -> activities.applyPaymentResult(orderId, message.attributes()));
                if (applied.is(Applied.PAID) || applied.is(Applied.ALREADY_PAID)) {
                    phase = "POST_PAYMENT";
                } else if (applied.is(Applied.CANCEL_STARTED) || applied.is(Applied.NON_CAPTURE)) {
                    phase = "CANCELLING";
                }
            }
            case WorkflowMessage.CANCEL_ORDER -> phase = "CANCELLING";
            case WorkflowMessage.NEXT_PAYMENT_ATTEMPT -> nextAttempt(message);
            default -> log.warn("Ignoring {} while awaiting payment", message.type());
        }
    }

    private void nextAttempt(WorkflowMessage message) {
        String operationId = message.attr("operation_id");
        NextAttempt next = durable(() -> activities.prepareNextAttempt(orderId, operationId, message.attr("return_url")));
        if (!next.eligible()) {
            return;
        }
        Outcome initiated = untilKnown(() -> activities.initiatePayment(orderId, next.attemptNumber(), operationId,
                next.returnUrl()));
        durableVoid(() -> activities.finishAttemptOperation(orderId, operationId, next.attemptNumber(),
                initiated.kind()));
    }

    // ------------------------------------------------------------------ CANCELLING (pre-payment compensation)

    private void cancel() {
        int attempt = 0;
        while (true) {
            CancelResult result = durable(() -> activities.cancelPayment(orderId));
            if ("CAPTURED".equals(result.result())) {
                phase = "POST_PAYMENT";
                return;
            }
            if ("NON_CAPTURE".equals(result.result()) || "NO_PAYMENT".equals(result.result())) {
                break;
            }
            attempt++;
            Workflow.await(backoff(attempt), () -> !queue.isEmpty());
            while (!queue.isEmpty()) {
                WorkflowMessage message = queue.poll();
                if (WorkflowMessage.PAYMENT_RESULT.equals(message.type())) {
                    Applied applied = durable(() -> activities.applyPaymentResult(orderId, message.attributes()));
                    if (applied.is(Applied.PAID) || applied.is(Applied.ALREADY_PAID)) {
                        phase = "POST_PAYMENT";
                        return;
                    }
                } else {
                    rejectLate(message, "The order is being cancelled");
                }
            }
        }
        // Payment non-capture is proven: both cleanup obligations run independently.
        List<Promise<Boolean>> cleanups = new ArrayList<>();
        for (String promotionId : promotionIds) {
            cleanups.add(Async.function(() -> untilAccepted(() -> activities.releasePromotion(orderId, promotionId))));
        }
        cleanups.add(Async.function(() -> untilAccepted(() -> activities.releaseInventory(orderId))));
        Promise.allOf(cleanups).get();
        durableVoid(() -> activities.completeCancellation(orderId));
        phase = "DONE";
    }

    private Boolean untilAccepted(io.temporal.workflow.Functions.Func<Outcome> call) {
        int attempt = 0;
        while (true) {
            Outcome outcome = durable(call);
            if (outcome.isAccepted()) {
                return Boolean.TRUE;
            }
            attempt++;
            Workflow.sleep(backoff(attempt));
        }
    }

    // ------------------------------------------------------------------ POST_PAYMENT (forward repair)

    private void postPayment() {
        Promise<Outcome> inventory = Async.function(() -> boundedReplay(() -> activities.commitInventory(orderId), 8));
        List<Promise<Outcome>> promotions = new ArrayList<>();
        for (String promotionId : promotionIds) {
            promotions.add(Async.function(() -> boundedReplay(() -> activities.confirmPromotion(orderId, promotionId), 8)));
        }
        Outcome inventoryOutcome = inventory.get();
        boolean repaired = true;
        if (!inventoryOutcome.isAccepted()) {
            repaired = false;
            durableVoid(() -> activities.escalatePostCapture(orderId, "POST_CAPTURE_INVENTORY_REPAIR",
                    String.valueOf(inventoryOutcome.errorCode())));
        }
        for (int i = 0; i < promotions.size(); i++) {
            Outcome outcome = promotions.get(i).get();
            String promotionId = promotionIds.get(i);
            if (!outcome.isAccepted()) {
                repaired = false;
                durableVoid(() -> activities.escalatePostCapture(orderId, "POST_CAPTURE_PROMOTION_REPAIR", promotionId));
            }
        }
        if (repaired) {
            durable(() -> activities.completePostPayment(orderId));
        }
        phase = "DONE";
    }

    // ------------------------------------------------------------------ DONE

    private void drainTerminal() {
        for (WorkflowMessage message : drain()) {
            if (WorkflowMessage.PAYMENT_RESULT.equals(message.type())) {
                durable(() -> activities.applyPaymentResult(orderId, message.attributes()));
            } else {
                rejectLate(message, "The checkout lane is closed");
            }
        }
    }

    private void rejectLate(WorkflowMessage message, String detail) {
        String operationId = message.attr("operation_id");
        if (operationId != null && WorkflowMessage.NEXT_PAYMENT_ATTEMPT.equals(message.type())) {
            durableVoid(() -> activities.rejectOperation(operationId, "PAYMENT_RETRY_NOT_ELIGIBLE", detail));
        }
    }
}
