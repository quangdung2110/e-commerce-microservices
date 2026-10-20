package com.ecommerce.order.benchmark.baseline;

import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.service.DispatchNudger;
import com.ecommerce.order.worker.WorkflowGateway;
import com.ecommerce.order.worker.ClosedTargetResolver;
import com.ecommerce.order.store.WorkflowOutboxStore.DispatchRow;
import com.ecommerce.order.store.WorkflowOutboxStore.MessageRow;
import com.ecommerce.order.workflow.WorkflowInput;
import com.ecommerce.order.workflow.WorkflowMessage;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Delivers durable workflow starts and messages to Temporal after commit (ADR-015).
 * Rows are leased with {@code FOR UPDATE SKIP LOCKED}; Temporal unavailability always
 * returns a row to RETRY, never to a terminal loss. Only invariant corruption quarantines.
 */

public class LegacyWorkflowDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LegacyWorkflowDispatcher.class);

    private final LegacyWorkflowOutboxStore store;
    private final ObjectProvider<WorkflowGateway> gateway;
    private final ClosedTargetResolver resolver;
    private final DispatchNudger nudger;
    private final TransactionTemplate tx;
    private final Json json;
    private final OrderProperties.Workers config;
    private final MeterRegistry meters;
    private volatile boolean running;
    private Thread thread;

    public LegacyWorkflowDispatcher(LegacyWorkflowOutboxStore store, ObjectProvider<WorkflowGateway> gateway,
                              ClosedTargetResolver resolver, DispatchNudger nudger, TransactionTemplate tx, Json json,
                              OrderProperties properties, MeterRegistry meters) {
        this.store = store;
        this.gateway = gateway;
        this.resolver = resolver;
        this.nudger = nudger;
        this.tx = tx;
        this.json = json;
        this.config = properties.workers();
        this.meters = meters;
    }

    /** One pass over due rows; returns the number of rows handled. Public for tests. */
    public int dispatchOnce() {
        WorkflowGateway target = gateway.getIfAvailable();
        if (target == null) {
            return 0;
        }
        int handled = 0;
        List<DispatchRow> dispatches = tx.execute(s -> store.claimDispatches(config.instanceId(),
                config.dispatchBatchSize(), config.lease()));
        for (DispatchRow row : dispatches) {
            handled++;
            WorkflowInput input = WorkflowInput.of(readMap(row.inputJson()));
            WorkflowGateway.Result result = target.start(row.workflowId(), row.workflowType(), row.taskQueue(), input);
            if (result == WorkflowGateway.Result.STARTED) {
                tx.executeWithoutResult(s -> store.dispatchStarted(row.dispatchId()));
            } else {
                tx.executeWithoutResult(s -> store.dispatchRetry(row.dispatchId(), "Temporal start failed",
                        retryDelay(row.attempts())));
            }
            meters.counter("order.workflow.dispatch", "result", result.name()).increment();
        }
        List<MessageRow> messages = tx.execute(s -> store.claimMessages(config.instanceId(),
                config.dispatchBatchSize(), config.lease()));
        for (MessageRow row : messages) {
            handled++;
            deliver(target, row);
        }
        return handled;
    }

    private void deliver(WorkflowGateway target, MessageRow row) {
        WorkflowMessage message = new WorkflowMessage(row.messageId(), row.messageType(), readMap(row.payloadJson()));
        WorkflowInput input = WorkflowInput.of(readMap(row.startInputJson()));
        WorkflowGateway.Result result = target.signalWithStart(row.targetWorkflowId(), row.workflowType(),
                row.taskQueue(), input, message);
        meters.counter("order.workflow.message", "type", row.messageType(), "result", result.name()).increment();
        switch (result) {
            case SIGNALLED -> tx.executeWithoutResult(s -> store.messageDelivered(row.messageId(), "SIGNALLED", null));
            case CLOSED_TARGET -> {
                ClosedTargetResolver.Resolution resolution;
                try {
                    resolution = resolver.resolve(row);
                } catch (RuntimeException e) {
                    log.warn("Closed-target resolution for {} failed: {}", row.messageId(), e.toString());
                    tx.executeWithoutResult(s -> store.messageRetry(row.messageId(), e.toString(),
                            retryDelay(row.attempts())));
                    return;
                }
                if (resolution == null) {
                    log.error("Message {} targets closed {} with ambiguous evidence; quarantined", row.messageId(),
                            row.targetWorkflowId());
                    tx.executeWithoutResult(s -> store.messageQuarantined(row.messageId(),
                            "Closed target with ambiguous evidence"));
                } else {
                    tx.executeWithoutResult(s -> store.messageDelivered(row.messageId(), resolution.outcome(),
                            resolution.replacementMessageId()));
                }
            }
            default -> tx.executeWithoutResult(s -> store.messageRetry(row.messageId(), "Temporal signal failed",
                    retryDelay(row.attempts())));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readMap(String value) {
        return json.read(value, Map.class);
    }

    private static Duration retryDelay(int attempts) {
        return Duration.ofMillis(Math.min(60_000L, 500L * (1L << Math.min(attempts, 7))));
    }

    @Override
    public void start() {
        if (!config.enabled()) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "order-workflow-dispatcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            try {
                if (dispatchOnce() == 0) {
                    nudger.await(config.dispatchInterval().toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Dispatcher pass failed: {}", e.toString());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
