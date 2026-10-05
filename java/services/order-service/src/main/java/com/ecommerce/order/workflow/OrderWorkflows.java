package com.ecommerce.order.workflow;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Workflow interfaces (interfaces.md §7). All share the single {@code onMessage} signal. */
public final class OrderWorkflows {

    private OrderWorkflows() {
    }

    @WorkflowInterface
    public interface Checkout {
        @WorkflowMethod(name = WorkflowNames.CHECKOUT_TYPE)
        void run(WorkflowInput input);

        @SignalMethod(name = WorkflowNames.MESSAGE_SIGNAL)
        void onMessage(WorkflowMessage message);
    }

    @WorkflowInterface
    public interface Refund {
        @WorkflowMethod(name = WorkflowNames.REFUND_TYPE)
        void run(WorkflowInput input);

        @SignalMethod(name = WorkflowNames.MESSAGE_SIGNAL)
        void onMessage(WorkflowMessage message);
    }

    @WorkflowInterface
    public interface Finalization {
        @WorkflowMethod(name = WorkflowNames.FINALIZATION_TYPE)
        void run(WorkflowInput input);

        @SignalMethod(name = WorkflowNames.MESSAGE_SIGNAL)
        void onMessage(WorkflowMessage message);
    }

    @WorkflowInterface
    public interface Reconciliation {
        @WorkflowMethod(name = WorkflowNames.RECONCILIATION_TYPE)
        void run(WorkflowInput input);

        @SignalMethod(name = WorkflowNames.MESSAGE_SIGNAL)
        void onMessage(WorkflowMessage message);
    }
}
