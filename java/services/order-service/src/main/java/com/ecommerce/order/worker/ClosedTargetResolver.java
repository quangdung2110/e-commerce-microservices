package com.ecommerce.order.worker;

import com.ecommerce.order.common.Json;
import com.ecommerce.order.domain.OperationTypes;
import com.ecommerce.order.service.CheckoutSaga;
import com.ecommerce.order.service.ProblemFactory;
import com.ecommerce.order.service.WorkflowEnqueuer;
import com.ecommerce.order.store.OperationStore;
import com.ecommerce.order.store.OrderStore;
import com.ecommerce.order.store.WorkflowOutboxStore.MessageRow;
import com.ecommerce.order.workflow.ActivityResults.Applied;
import com.ecommerce.order.workflow.WorkflowMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * A message whose target saga already closed is never delivered by restarting the saga
 * (INV-ORD-033). The DB proves it applied/superseded, or the obligation is rerouted
 * transactionally to the named recovery owner; anything ambiguous stays quarantined.
 */
@Component
public class ClosedTargetResolver {

    public record Resolution(String outcome, String replacementMessageId) {
        static Resolution of(String outcome) {
            return new Resolution(outcome, null);
        }
    }

    private final OrderStore orders;
    private final OperationStore operations;
    private final CheckoutSaga checkout;
    private final WorkflowEnqueuer enqueuer;
    private final ProblemFactory problems;
    private final TransactionTemplate tx;
    private final Json json;

    public ClosedTargetResolver(OrderStore orders, OperationStore operations,
                                CheckoutSaga checkout, WorkflowEnqueuer enqueuer, ProblemFactory problems,
                                TransactionTemplate tx, Json json) {
        this.orders = orders;
        this.operations = operations;
        this.checkout = checkout;
        this.enqueuer = enqueuer;
        this.problems = problems;
        this.tx = tx;
        this.json = json;
    }

    /** Returns ALREADY_APPLIED, STALE_NOOP or REROUTED; null when ambiguous (quarantine). */
    public Resolution resolve(MessageRow row) {
        Map<String, String> attrs = readAttributes(row.payloadJson());
        Long orderId = row.orderId();
        if (orderId == null) {
            return null;
        }
        return switch (row.messageType()) {
            case WorkflowMessage.PAYMENT_RESULT -> paymentResult(orderId, attrs);
            case WorkflowMessage.CANCEL_ORDER, WorkflowMessage.NO_PAYOUT_DECIDED,
                    WorkflowMessage.INSPECTION_COMPLETED -> Resolution.of("STALE_NOOP");
            case WorkflowMessage.NEXT_PAYMENT_ATTEMPT -> {
                tx.executeWithoutResult(s -> operations.transition(UUID.fromString(attrs.get("operation_id")),
                        OperationTypes.Status.REJECTED, null, json.write(problems.operationProblem(
                                "PAYMENT_RETRY_NOT_ELIGIBLE", "The checkout lane is closed"))));
                yield Resolution.of("STALE_NOOP");
            }
            case WorkflowMessage.REFUND_RESULT -> reroute(row, orderId, attrs);
            default -> null;
        };
    }

    private Resolution paymentResult(long orderId, Map<String, String> attrs) {
        String txn = attrs.get("payment_transaction_id");
        OrderStore.OrderRow order = orders.find(orderId).orElseThrow();
        if (!"SUCCESS".equals(attrs.get("result"))) {
            return order.status().isPrePayment() ? null : Resolution.of("STALE_NOOP");
        }
        if (txn.equals(order.capturedTransactionId())) {
            return Resolution.of("ALREADY_APPLIED");
        }
        if (order.status().isPrePayment()) {
            return null;
        }
        // Terminal non-capture or a second capture: markPaid opens the reconciliation obligation.
        Applied applied = checkout.markPaid(orderId, txn, Long.parseLong(attrs.get("amount_minor")));
        return Resolution.of(applied.is(Applied.ANOMALY) ? "REROUTED" : "ALREADY_APPLIED");
    }

    private Resolution reroute(MessageRow row, long orderId, Map<String, String> attrs) {
        String replacement = row.messageId() + ":rerouted";
        tx.executeWithoutResult(s -> {
            orders.lock(orderId);
            enqueuer.message(orderId, enqueuer.reconciliation(orderId), replacement, row.messageType(), attrs);
        });
        return new Resolution("REROUTED", replacement);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readAttributes(String payload) {
        return json.read(payload, Map.class);
    }
}
