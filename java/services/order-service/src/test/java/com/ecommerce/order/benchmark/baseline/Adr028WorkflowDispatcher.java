package com.ecommerce.order.benchmark.baseline;

import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.service.DispatchNudger;
import com.ecommerce.order.worker.ClosedTargetResolver;
import com.ecommerce.order.worker.WorkflowGateway;
import com.ecommerce.order.store.WorkflowOutboxStore.DispatchRow;
import com.ecommerce.order.store.WorkflowOutboxStore.DispatchCompletion;
import com.ecommerce.order.store.WorkflowOutboxStore.MessageRow;
import com.ecommerce.order.workflow.WorkflowInput;
import com.ecommerce.order.workflow.WorkflowMessage;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Frozen ADR-028 production dispatcher, for the paired ADR-029 benchmark only (never a Spring bean):
 * eight start workers with fenced batch ACK, then sequential per-row SignalWithStart and unfenced
 * per-row message ACK on the coordinator. Only the package, class name and store type differ from
 * the 2026-10-16 production source.
 */
public class Adr028WorkflowDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Adr028WorkflowDispatcher.class);

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
    private final Object lifecycle = new Object();
    private volatile ThreadPoolExecutor executor;

    public Adr028WorkflowDispatcher(LegacyWorkflowOutboxStore store, ObjectProvider<WorkflowGateway> gateway,
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
        meters.gauge("order.workflow.dispatch.active", this,
                d -> d.executor == null ? 0 : d.executor.getActiveCount());
    }

    /** One pass over due rows; returns the number of rows handled. Public for tests. */
    public synchronized int dispatchOnce() {
        WorkflowGateway target = gateway.getIfAvailable();
        if (target == null) {
            return 0;
        }
        int handled = 0;
        List<DispatchRow> dispatches = tx.execute(s -> store.claimDispatches(config.instanceId(),
                config.dispatchBatchSize(), config.lease()));
        handled += dispatchStarts(target, dispatches);
        List<MessageRow> messages = tx.execute(s -> store.claimMessages(config.instanceId(),
                config.dispatchBatchSize(), config.lease()));
        for (MessageRow row : messages) {
            handled++;
            deliver(target, row);
        }
        return handled;
    }

    /** Only the coordinator accesses the DB. RPC workers never hold JDBC connections/transactions. */
    private int dispatchStarts(WorkflowGateway target, List<DispatchRow> rows) {
        if (rows.isEmpty()) return 0;
        var completions = new ExecutorCompletionService<DispatchCompletion>(executor());
        var unsent = new ArrayDeque<>(rows);
        var inFlight = new HashMap<Future<DispatchCompletion>, DispatchRow>();
        var outstanding = new HashMap<UUID, DispatchRow>();
        rows.forEach(row -> outstanding.put(row.dispatchId(), row));
        var ready = new ArrayList<DispatchCompletion>();
        long flushAt = Long.MAX_VALUE;
        long renewEvery = config.lease().dividedBy(3).toNanos();
        long renewAt = System.nanoTime() + renewEvery;
        try {
            refill(target, completions, unsent, inFlight);
            while (!inFlight.isEmpty()) {
                long now = System.nanoTime();
                if (!ready.isEmpty() && (ready.size() >= config.dispatchAckBatchSize() || now >= flushAt)) {
                    flush(ready, outstanding);
                    flushAt = Long.MAX_VALUE;
                    now = System.nanoTime();
                }
                if (now >= renewAt) {
                    var owned = new HashSet<>(tx.execute(s -> store.renewDispatches(config.instanceId(),
                            List.copyOf(outstanding.values()), config.lease())));
                    // Do not send queued work that another claim generation now owns.
                    unsent.removeIf(row -> !owned.contains(row.dispatchId()));
                    outstanding.keySet().retainAll(owned);
                    renewAt = System.nanoTime() + renewEvery;
                    now = System.nanoTime();
                }
                long wait = Math.max(1, Math.min(renewAt, flushAt) - now);
                Future<DispatchCompletion> done = completions.poll(wait, TimeUnit.NANOSECONDS);
                if (done == null) continue;
                do {
                    DispatchRow row = inFlight.remove(done);
                    DispatchCompletion result;
                    try {
                        result = done.get();
                    } catch (ExecutionException e) {
                        result = DispatchCompletion.retry(row, e.getCause().toString(), retryDelay(row.attempts()));
                    }
                    if (ready.isEmpty()) flushAt = System.nanoTime() + config.dispatchAckInterval().toNanos();
                    ready.add(result);
                    meters.counter("order.workflow.dispatch", "result", result.started() ? "STARTED" : "RETRY").increment();
                    if (ready.size() >= config.dispatchAckBatchSize()) {
                        flush(ready, outstanding);
                        flushAt = Long.MAX_VALUE;
                    }
                } while ((done = completions.poll()) != null);
                refill(target, completions, unsent, inFlight);
            }
            flush(ready, outstanding);
            return rows.size();
        } catch (InterruptedException interrupted) {
            // Already acknowledged RPCs can still be persisted; unknown/cancelled RPCs remain leased for replay.
            try { flush(ready, outstanding); }
            finally { Thread.currentThread().interrupt(); }
            throw new IllegalStateException("Workflow dispatch interrupted", interrupted);
        } finally {
            inFlight.keySet().forEach(future -> future.cancel(true));
        }
    }

    private void refill(WorkflowGateway target, ExecutorCompletionService<DispatchCompletion> completions,
                        ArrayDeque<DispatchRow> unsent, Map<Future<DispatchCompletion>, DispatchRow> inFlight) {
        while (!unsent.isEmpty() && inFlight.size() < config.dispatchThreads()) {
            DispatchRow row = unsent.removeFirst();
            Future<DispatchCompletion> future = completions.submit(() -> {
                try {
                    WorkflowInput input = WorkflowInput.of(readMap(row.inputJson()));
                    WorkflowGateway.Result result = target.start(row.workflowId(), row.workflowType(), row.taskQueue(), input);
                    return result == WorkflowGateway.Result.STARTED ? DispatchCompletion.started(row)
                            : DispatchCompletion.retry(row, "Temporal start failed", retryDelay(row.attempts()));
                } catch (RuntimeException e) {
                    log.warn("Dispatch {} failed: {}", row.dispatchId(), e.toString());
                    return DispatchCompletion.retry(row, e.toString(), retryDelay(row.attempts()));
                }
            });
            inFlight.put(future, row);
        }
    }

    private void flush(List<DispatchCompletion> ready, Map<UUID, DispatchRow> outstanding) {
        if (ready.isEmpty()) return;
        List<DispatchCompletion> batch = List.copyOf(ready);
        List<UUID> applied = tx.execute(s -> store.completeDispatches(config.instanceId(), batch));
        meters.summary("order.workflow.dispatch.ack.batch").record(batch.size());
        if (applied.size() < batch.size()) {
            meters.counter("order.workflow.dispatch.ack.stale").increment(batch.size() - applied.size());
        }
        batch.forEach(result -> outstanding.remove(result.dispatchId()));
        ready.clear();
    }

    private ThreadPoolExecutor executor() {
        synchronized (lifecycle) {
            if (executor == null) {
                AtomicInteger sequence = new AtomicInteger();
                executor = new ThreadPoolExecutor(config.dispatchThreads(), config.dispatchThreads(),
                        0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(config.dispatchThreads()), task -> {
                    Thread worker = new Thread(task, "order-workflow-start-" + sequence.incrementAndGet());
                    worker.setDaemon(true);
                    return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
            }
            return executor;
        }
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
        synchronized (lifecycle) {
            if (!config.enabled() || running) return;
            if (executor != null && executor.isShutdown()) {
                if (!executor.isTerminated()) throw new IllegalStateException("Previous dispatcher RPCs still stopping");
                executor = null;
            }
            executor();
            running = true;
            thread = new Thread(this::loop, "order-workflow-dispatcher");
            thread.setDaemon(true);
            thread.start();
        }
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
                if (Thread.currentThread().isInterrupted()) return;
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
        Thread coordinator;
        ThreadPoolExecutor pool;
        synchronized (lifecycle) {
            running = false;
            coordinator = thread;
            pool = executor;
            if (coordinator != null) coordinator.interrupt();
            if (pool != null) pool.shutdownNow();
        }
        try {
            if (coordinator != null && coordinator != Thread.currentThread()) coordinator.join(5000);
            if (pool != null) pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
