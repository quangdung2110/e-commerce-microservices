package com.ecommerce.order.worker;

import com.ecommerce.order.workflow.WorkflowInput;
import com.ecommerce.order.workflow.WorkflowMessage;
import com.ecommerce.order.workflow.WorkflowNames;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts/attaches fixed Workflow IDs and delivers messages with SignalWithStart. Business
 * workflows reject duplicate IDs so a closed saga is never restarted; finalization and
 * reconciliation only replay frozen commands and allow a new run (ADR-020 §13).
 * Called concurrently from the dispatcher's RPC pool (ADR-028/029): every call builds its own stub
 * over the shared, thread-safe {@link WorkflowClient} and touches no database.
 */
public class TemporalWorkflowGateway implements WorkflowGateway {

    private static final Logger log = LoggerFactory.getLogger(TemporalWorkflowGateway.class);

    private final WorkflowClient client;

    public TemporalWorkflowGateway(WorkflowClient client) {
        this.client = client;
    }

    @Override
    public Result start(String workflowId, String workflowType, String taskQueue, WorkflowInput input) {
        try {
            stub(workflowId, workflowType, taskQueue).start(input);
            return Result.STARTED;
        } catch (WorkflowExecutionAlreadyStarted attached) {
            return Result.STARTED;
        } catch (RuntimeException e) {
            log.warn("Temporal start {} failed: {}", workflowId, e.toString());
            return Result.RETRY;
        }
    }

    @Override
    public Result signalWithStart(String workflowId, String workflowType, String taskQueue, WorkflowInput input,
                                  WorkflowMessage message) {
        try {
            stub(workflowId, workflowType, taskQueue).signalWithStart(WorkflowNames.MESSAGE_SIGNAL,
                    new Object[]{message}, new Object[]{input});
            return Result.SIGNALLED;
        } catch (WorkflowExecutionAlreadyStarted closed) {
            return Result.CLOSED_TARGET;
        } catch (RuntimeException e) {
            log.warn("Temporal SignalWithStart {} failed: {}", workflowId, e.toString());
            return Result.RETRY;
        }
    }

    private WorkflowStub stub(String workflowId, String workflowType, String taskQueue) {
        return client.newUntypedWorkflowStub(workflowType, WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(taskQueue)
                .setWorkflowIdReusePolicy(reusePolicy(workflowType))
                .build());
    }

    static WorkflowIdReusePolicy reusePolicy(String workflowType) {
        return switch (workflowType) {
            case WorkflowNames.FINALIZATION_TYPE, WorkflowNames.RECONCILIATION_TYPE ->
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE;
            default -> WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
        };
    }
}
