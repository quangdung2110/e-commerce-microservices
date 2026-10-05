package com.ecommerce.order.worker;

import com.ecommerce.order.workflow.WorkflowInput;
import com.ecommerce.order.workflow.WorkflowMessage;

/** Temporal boundary used by the dispatcher; tests substitute the Temporal test environment. */
public interface WorkflowGateway {

    enum Result { STARTED, SIGNALLED, CLOSED_TARGET, RETRY }

    Result start(String workflowId, String workflowType, String taskQueue, WorkflowInput input);

    Result signalWithStart(String workflowId, String workflowType, String taskQueue, WorkflowInput input,
                           WorkflowMessage message);
}
