package com.ecommerce.order.worker;

import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.service.DispatchNudger;
import com.ecommerce.order.store.WorkflowOutboxStore;
import com.ecommerce.order.store.WorkflowOutboxStore.DispatchRow;
import com.ecommerce.order.store.WorkflowOutboxStore.DispatchCompletion;
import com.ecommerce.order.store.WorkflowOutboxStore.MessageCompletion;
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
import java.time.Instant;
import java.util.Comparator;
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
 * Delivers durable workflow starts and messages to Temporal after commit (ADR-015).
 * Rows are leased with {@code FOR UPDATE SKIP LOCKED}; Temporal unavailability always
 * returns a row to RETRY, never to a terminal loss. Only invariant corruption quarantines.
 * Starts (ADR-028) and messages (ADR-029) share one bounded RPC pool; a target workflow has at
 * most one message in flight, sent in {@code created_at} order. ADR-030 replenishes bounded windows
 * while earlier RPCs are outstanding. Only the coordinator touches the DB.
 */
@Component
public class WorkflowDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDispatcher.class);
    private static final Comparator<MessageRow> CLAIM_ORDER = Comparator.comparing(MessageRow::createdAt,
            Comparator.nullsFirst(Comparator.<Instant>naturalOrder())).thenComparing(MessageRow::messageId);

    private final WorkflowOutboxStore store;
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
    /** Bounded by the message window; survives an idle pass, so later claims cannot bypass a local retry head. */
    private final Map<String, Long> retryLanes = new HashMap<>();
    private volatile int claimedRows;

    public WorkflowDispatcher(WorkflowOutboxStore store, ObjectProvider<WorkflowGateway> gateway,
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
        meters.gauge("order.workflow.dispatch.claimed", this, d -> d.claimedRows);
    }

    /** Drains currently available work with bounded rolling claims; returns total rows claimed. Public for tests. */
    public synchronized int dispatchOnce() {
        WorkflowGateway target = gateway.getIfAvailable();
        if (target == null) {
            return 0;
        }
        return new Pass(target).run();
    }

    private record Claim(List<DispatchRow> starts, List<MessageRow> messages) { }

    private record Signal(MessageRow row, WorkflowGateway.Result result, String error) { }

    private record Acked(List<UUID> starts, List<String> messages) { }

    /**
     * One rolling drain of starts and messages. RPC workers never hold JDBC connections/transactions;
     * closed-target resolution, ACK, renewal and every other DB call run on the coordinator thread.
     */
    private final class Pass {
        private final WorkflowGateway target;
        private final ExecutorCompletionService<Object> completions = new ExecutorCompletionService<>(executor());
        private final Map<Future<Object>, Object> inFlight = new HashMap<>();
        private final ArrayDeque<DispatchRow> starts = new ArrayDeque<>();
        /** Unsent messages per target workflow; a lane is ready only while none of its messages is in flight. */
        private final Map<String, ArrayDeque<MessageRow>> lanes = new HashMap<>();
        private final ArrayDeque<String> readyLanes = new ArrayDeque<>();
        private final Map<UUID, DispatchRow> outstandingStarts = new HashMap<>();
        private final Map<String, MessageRow> outstandingMessages = new HashMap<>();
        private final List<DispatchCompletion> readyStarts = new ArrayList<>();
        private final List<MessageCompletion> readyMessages = new ArrayList<>();
        private boolean messageTurn;
        private long flushAt = Long.MAX_VALUE;
        private long startPollAt;
        private long messagePollAt;
        private int totalClaimed;

        Pass(WorkflowGateway target) {
            this.target = target;
        }

        private void add(Claim claim) {
            totalClaimed += claim.starts().size() + claim.messages().size();
            starts.addAll(claim.starts());
            claim.starts().forEach(row -> outstandingStarts.put(row.dispatchId(), row));
            // UPDATE ... RETURNING has no defined order; lanes follow created_at, ties by message ID.
            claim.messages().stream().sorted(CLAIM_ORDER).forEach(row -> {
                outstandingMessages.put(row.messageId(), row);
                lanes.computeIfAbsent(row.targetWorkflowId(), id -> {
                    readyLanes.addLast(id);
                    return new ArrayDeque<>();
                }).addLast(row);
            });
            claimedRows = outstandingStarts.size() + outstandingMessages.size();
        }

        int run() {
            long renewEvery = config.lease().dividedBy(3).toNanos();
            long renewAt = System.nanoTime() + renewEvery;
            try {
                while (true) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    long now = System.nanoTime();
                    if (pending() > 0 && (pending() >= config.dispatchAckBatchSize() || now >= flushAt)) {
                        flush();
                        now = System.nanoTime();
                    }
                    if (now >= renewAt) {
                        renew();
                        renewAt = System.nanoTime() + renewEvery;
                        now = System.nanoTime();
                    }
                    claimMore();
                    refill();
                    if (inFlight.isEmpty()) {
                        flush();
                        return totalClaimed;
                    }
                    now = System.nanoTime();
                    // Poll even when a slow RPC is the only completion left: newly committed rows may arrive meanwhile.
                    long wait = Math.max(1, Math.min(Math.min(renewAt, flushAt),
                            now + config.dispatchInterval().toNanos()) - now);
                    Future<Object> done = completions.poll(wait, TimeUnit.NANOSECONDS);
                    if (done == null) continue;
                    do {
                        complete(done);
                    } while ((done = completions.poll()) != null);
                }
            } catch (InterruptedException interrupted) {
                // Already acknowledged RPCs can still be persisted; unknown/cancelled/unsent rows remain leased for replay.
                try { flush(); }
                finally { Thread.currentThread().interrupt(); }
                throw new IllegalStateException("Workflow dispatch interrupted", interrupted);
            } finally {
                inFlight.keySet().forEach(future -> future.cancel(true));
                claimedRows = 0;
            }
        }

        /** Each queue owns at most batchSize rows including in-flight RPCs and unflushed ACKs. */
        private void claimMore() {
            long now = System.nanoTime();
            retryLanes.values().removeIf(until -> until <= now);
            int capacity = config.dispatchBatchSize();
            int startLimit = outstandingStarts.size() <= capacity / 2 && now >= startPollAt
                    ? capacity - outstandingStarts.size() : 0;
            int messageUsed = outstandingMessages.size() + retryLanes.size();
            int messageLimit = messageUsed <= capacity / 2 && now >= messagePollAt ? capacity - messageUsed : 0;
            if (startLimit == 0 && messageLimit == 0) return;
            var excluded = new HashSet<>(retryLanes.keySet());
            outstandingMessages.values().forEach(row -> excluded.add(row.targetWorkflowId()));
            Claim claim = tx.execute(s -> new Claim(
                    startLimit == 0 ? List.of() : outstandingStarts.isEmpty()
                            ? store.claimDispatches(config.instanceId(), startLimit, config.lease())
                            : store.claimDispatches(config.instanceId(), startLimit, config.lease(), List.copyOf(outstandingStarts.keySet())),
                    messageLimit == 0 ? List.of() : excluded.isEmpty()
                            ? store.claimMessages(config.instanceId(), messageLimit, config.lease())
                            : store.claimMessages(config.instanceId(), messageLimit, config.lease(), List.copyOf(excluded))));
            long idlePollAt = System.nanoTime() + config.dispatchInterval().toNanos();
            if (startLimit > 0) startPollAt = claim.starts().size() < startLimit ? idlePollAt : 0;
            if (messageLimit > 0) messagePollAt = claim.messages().size() < messageLimit ? idlePollAt : 0;
            add(claim);
        }

        /** Alternates between the two queues so neither kind waits for the other's whole group. */
        private void refill() {
            while (inFlight.size() < config.dispatchThreads()) {
                boolean sent = messageTurn ? sendMessage() || sendStart() : sendStart() || sendMessage();
                if (!sent) return;
            }
        }

        private boolean sendStart() {
            DispatchRow row = starts.pollFirst();
            if (row == null) return false;
            inFlight.put(completions.submit(() -> {
                try {
                    WorkflowInput input = WorkflowInput.of(readMap(row.inputJson()));
                    WorkflowGateway.Result result = target.start(row.workflowId(), row.workflowType(), row.taskQueue(), input);
                    return result == WorkflowGateway.Result.STARTED ? DispatchCompletion.started(row)
                            : DispatchCompletion.retry(row, "Temporal start failed", retryDelay(row.attempts()));
                } catch (RuntimeException e) {
                    log.warn("Dispatch {} failed: {}", row.dispatchId(), e.toString());
                    return DispatchCompletion.retry(row, e.toString(), retryDelay(row.attempts()));
                }
            }), row);
            messageTurn = true;
            return true;
        }

        private boolean sendMessage() {
            String lane;
            while ((lane = readyLanes.pollFirst()) != null) {
                ArrayDeque<MessageRow> queued = lanes.get(lane);
                if (queued.isEmpty()) {
                    lanes.remove(lane);
                    continue;
                }
                MessageRow row = queued.pollFirst();
                inFlight.put(completions.submit(() -> {
                    try {
                        WorkflowMessage message = new WorkflowMessage(row.messageId(), row.messageType(),
                                readMap(row.payloadJson()));
                        WorkflowInput input = WorkflowInput.of(readMap(row.startInputJson()));
                        return new Signal(row, target.signalWithStart(row.targetWorkflowId(), row.workflowType(),
                                row.taskQueue(), input, message), null);
                    } catch (RuntimeException e) {
                        log.warn("Message {} failed: {}", row.messageId(), e.toString());
                        return new Signal(row, WorkflowGateway.Result.RETRY, e.toString());
                    }
                }), row);
                messageTurn = false;
                return true;
            }
            return false;
        }

        private void complete(Future<Object> done) throws InterruptedException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Object row = inFlight.remove(done);
            Object result;
            try {
                result = done.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof InterruptedException interrupted) throw interrupted;
                result = row instanceof DispatchRow start
                        ? DispatchCompletion.retry(start, e.getCause().toString(), retryDelay(start.attempts()))
                        : new Signal((MessageRow) row, WorkflowGateway.Result.RETRY, e.getCause().toString());
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (result instanceof DispatchCompletion start) {
                meters.counter("order.workflow.dispatch", "result", start.started() ? "STARTED" : "RETRY").increment();
                ready(start);
            } else {
                signalled((Signal) result);
            }
        }

        private void signalled(Signal signal) {
            MessageRow row = signal.row();
            meters.counter("order.workflow.message", "type", row.messageType(), "result", signal.result().name())
                    .increment();
            MessageCompletion completion = switch (signal.result()) {
                case SIGNALLED -> MessageCompletion.delivered(row, "SIGNALLED", null);
                case CLOSED_TARGET -> resolveClosedTarget(row);
                default -> MessageCompletion.retry(row, signal.error() == null ? "Temporal signal failed"
                        : signal.error(), retryDelay(row.attempts()));
            };
            ready(completion);
            ArrayDeque<MessageRow> queued = lanes.get(row.targetWorkflowId());
            if (completion.retrying()) {
                // Keep the target excluded until its retry ACK commits. On an ACK failure this conservative
                // deadline also covers lease expiry; a subsequent successful ACK shortens it to the real backoff.
                retryLanes.put(row.targetWorkflowId(), System.nanoTime() + config.lease().toNanos()
                        + completion.backoff().toNanos());
                // Successors never overtake a head that will be retried: release them unsent, due together with it.
                for (MessageRow next : queued) {
                    meters.counter("order.workflow.message", "type", next.messageType(), "result", "DEFERRED")
                            .increment();
                    ready(MessageCompletion.retry(next, "Deferred behind message " + row.messageId(),
                            completion.backoff()));
                }
                queued.clear();
            }
            if (queued.isEmpty()) lanes.remove(row.targetWorkflowId());
            else readyLanes.addLast(row.targetWorkflowId());
        }

        /** Needs the DB, so it runs here on the coordinator; closed targets are rare. */
        private MessageCompletion resolveClosedTarget(MessageRow row) {
            ClosedTargetResolver.Resolution resolution;
            try {
                resolution = resolver.resolve(row);
            } catch (RuntimeException e) {
                log.warn("Closed-target resolution for {} failed: {}", row.messageId(), e.toString());
                return MessageCompletion.retry(row, e.toString(), retryDelay(row.attempts()));
            }
            if (resolution == null) {
                log.error("Message {} targets closed {} with ambiguous evidence; quarantined", row.messageId(),
                        row.targetWorkflowId());
                return MessageCompletion.quarantined(row, "Closed target with ambiguous evidence");
            }
            return MessageCompletion.delivered(row, resolution.outcome(), resolution.replacementMessageId());
        }

        private void ready(Object completion) {
            if (pending() == 0) flushAt = System.nanoTime() + config.dispatchAckInterval().toNanos();
            if (completion instanceof DispatchCompletion start) readyStarts.add(start);
            else readyMessages.add((MessageCompletion) completion);
            if (pending() >= config.dispatchAckBatchSize()) flush();
        }

        private int pending() {
            return readyStarts.size() + readyMessages.size();
        }

        /** Both kinds in one transaction, one fenced statement per table. */
        private void flush() {
            if (pending() == 0) return;
            List<DispatchCompletion> startBatch = List.copyOf(readyStarts);
            List<MessageCompletion> messageBatch = List.copyOf(readyMessages);
            Acked acked = tx.execute(s -> new Acked(
                    startBatch.isEmpty() ? List.of() : store.completeDispatches(config.instanceId(), startBatch),
                    messageBatch.isEmpty() ? List.of() : store.completeMessages(config.instanceId(), messageBatch)));
            if (!startBatch.isEmpty()) {
                meters.summary("order.workflow.dispatch.ack.batch").record(startBatch.size());
                if (acked.starts().size() < startBatch.size()) {
                    meters.counter("order.workflow.dispatch.ack.stale").increment(startBatch.size() - acked.starts().size());
                }
            }
            if (!messageBatch.isEmpty()) {
                meters.summary("order.workflow.message.ack.batch").record(messageBatch.size());
                if (acked.messages().size() < messageBatch.size()) {
                    meters.counter("order.workflow.message.ack.stale")
                            .increment(messageBatch.size() - acked.messages().size());
                }
            }
            long acknowledgedAt = System.nanoTime();
            var acknowledgedMessages = new HashSet<>(acked.messages());
            messageBatch.stream().filter(result -> result.retrying() && acknowledgedMessages.contains(result.messageId()))
                    .forEach(result -> retryLanes.put(outstandingMessages.get(result.messageId()).targetWorkflowId(),
                            acknowledgedAt + result.backoff().toNanos()));
            startBatch.forEach(result -> outstandingStarts.remove(result.dispatchId()));
            messageBatch.forEach(result -> outstandingMessages.remove(result.messageId()));
            readyStarts.clear();
            readyMessages.clear();
            claimedRows = outstandingStarts.size() + outstandingMessages.size();
            flushAt = Long.MAX_VALUE;
        }

        private void renew() {
            List<DispatchRow> startRows = List.copyOf(outstandingStarts.values());
            List<MessageRow> messageRows = List.copyOf(outstandingMessages.values());
            if (startRows.isEmpty() && messageRows.isEmpty()) return;
            Acked owned = tx.execute(s -> new Acked(
                    startRows.isEmpty() ? List.of() : store.renewDispatches(config.instanceId(), startRows, config.lease()),
                    messageRows.isEmpty() ? List.of() : store.renewMessages(config.instanceId(), messageRows, config.lease())));
            // Do not send queued work that another claim generation now owns.
            var ownedStarts = new HashSet<>(owned.starts());
            starts.removeIf(row -> !ownedStarts.contains(row.dispatchId()));
            var ownedMessages = new HashSet<>(owned.messages());
            lanes.values().forEach(queued -> queued.removeIf(row -> !ownedMessages.contains(row.messageId())));
            // Keep already-sent/unflushed rows in the bounded window and excluded from new claims even
            // after losing ownership. Their late ACK is fenced; unsent rows alone can be discarded now.
            inFlight.values().forEach(row -> {
                if (row instanceof DispatchRow start) ownedStarts.add(start.dispatchId());
                else ownedMessages.add(((MessageRow) row).messageId());
            });
            readyStarts.forEach(result -> ownedStarts.add(result.dispatchId()));
            readyMessages.forEach(result -> ownedMessages.add(result.messageId()));
            outstandingStarts.keySet().retainAll(ownedStarts);
            outstandingMessages.keySet().retainAll(ownedMessages);
            claimedRows = outstandingStarts.size() + outstandingMessages.size();
        }
    }

    private ThreadPoolExecutor executor() {
        synchronized (lifecycle) {
            if (executor == null) {
                AtomicInteger sequence = new AtomicInteger();
                executor = new ThreadPoolExecutor(config.dispatchThreads(), config.dispatchThreads(),
                        0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(config.dispatchThreads()), task -> {
                    Thread worker = new Thread(task, "order-workflow-rpc-" + sequence.incrementAndGet());
                    worker.setDaemon(true);
                    return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
            }
            return executor;
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
