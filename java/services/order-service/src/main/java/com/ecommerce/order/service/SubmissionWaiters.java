package com.ecommerce.order.service;

import com.ecommerce.order.common.ApiException;
import com.ecommerce.order.common.ErrorCode;
import com.ecommerce.order.common.RequestCorrelation;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.config.SubmissionWaiterProperties;
import com.ecommerce.order.store.SubmissionStore;
import com.ecommerce.order.store.SubmissionStore.CheckpointRow;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * In-memory HTTP responses only: the DB owns every submission/result. Register before reading
 * the checkpoint, then wake on committed notifications. A bounded batch sweep repairs missed
 * notifications. No sleeping thread, transaction or JDBC connection is retained per waiter.
 */
@Component
@EnableConfigurationProperties(SubmissionWaiterProperties.class)
public class SubmissionWaiters {
    private static final Logger log = LoggerFactory.getLogger(SubmissionWaiters.class);
    private final SubmissionStore submissions;
    private final SubmissionWaiterProperties config;
    private final Duration responseAllowance;
    private final Map<UUID, Set<Waiter>> waiting = new ConcurrentHashMap<>();
    private final Semaphore capacity;
    private final ThreadPoolExecutor responses;
    private final ScheduledThreadPoolExecutor timers;
    private final AtomicBoolean sweeping = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final MeterRegistry meters;

    public SubmissionWaiters(SubmissionStore submissions, SubmissionWaiterProperties config,
                             OrderProperties properties, MeterRegistry meters) {
        this.submissions = submissions;
        this.config = config;
        this.meters = meters;
        this.responseAllowance = properties.clients().paymentRead()
                .plus(properties.clients().connectTimeout()).plusSeconds(1);
        capacity = new Semaphore(config.maxWaiters());
        // At most one check/response task per waiter, plus one shared recovery sweep.
        responses = new ThreadPoolExecutor(config.responseThreads(), config.responseThreads(), 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(config.maxWaiters() + 1),
                Thread.ofPlatform().daemon().name("order-submission-response-", 0).factory());
        timers = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("order-submission-timer-", 0).factory());
        timers.setRemoveOnCancelPolicy(true);
        meters.gauge("order.checkout.waiters", this, w -> config.maxWaiters() - capacity.availablePermits());
        meters.gauge("order.checkout.response.active", responses, ThreadPoolExecutor::getActiveCount);
        meters.gauge("order.checkout.response.queued", responses, executor -> executor.getQueue().size());
        timers.scheduleWithFixedDelay(this::reconcile, config.recoveryInterval().toMillis(),
                config.recoveryInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    public DeferredResult<ResponseEntity<Object>> await(UUID token, Duration checkpointBudget,
            RequestCorrelation correlation, Function<CheckpointRow, ResponseEntity<Object>> render) {
        Duration responseBudget = checkpointBudget.plus(responseAllowance);
        if (correlation != null && correlation.remaining() != null
                && correlation.remaining().compareTo(responseBudget) < 0) {
            responseBudget = correlation.remaining();
        }
        long responseMs = Math.max(1, responseBudget.toMillis());
        DeferredResult<ResponseEntity<Object>> result = new DeferredResult<>(responseMs);
        if (closed.get() || !capacity.tryAcquire()) {
            meters.counter("order.checkout.waiter.rejected").increment();
            result.setErrorResult(unavailable());
            return result;
        }
        Waiter waiter = new Waiter(token, result, correlation, render);
        waiting.compute(token, (key, entries) -> {
            Set<Waiter> next = entries == null ? ConcurrentHashMap.newKeySet() : entries;
            next.add(waiter);
            return next;
        });
        result.onCompletion(waiter::abandon);
        result.onError(error -> waiter.abandon());
        result.onTimeout(() -> waiter.fail(waiter.phase.get() == 0 ? inProgress() : paymentUnavailable()));
        try {
            waiter.checkpointTimer = timers.schedule(() -> {
                if (waiter.phase.compareAndSet(0, 2)) {
                    try { waiter.result.setErrorResult(inProgress()); }
                    finally { waiter.cleanup(); }
                }
            }, Math.max(0, checkpointBudget.toNanos()), TimeUnit.NANOSECONDS);
            waiter.responseTimer = timers.schedule(
                    () -> waiter.fail(waiter.phase.get() == 0 ? inProgress() : paymentUnavailable()),
                    responseMs, TimeUnit.MILLISECONDS);
            if (waiter.phase.get() == 2) {
                waiter.cancelTimers();
            } else {
                waiter.check(); // Closes the commit-before-registration race, including replay.
            }
        } catch (RejectedExecutionException shutdown) {
            waiter.fail(inProgress());
        }
        return result;
    }

    /** Called by the dedicated listener; it never runs SQL or remote HTTP on that thread. */
    public void signal(UUID token) {
        Set<Waiter> entries = waiting.get(token);
        if (entries != null) {
            entries.forEach(Waiter::check);
        }
    }

    /** Startup/reconnect and periodic repair share one bounded task, not a poll per request. */
    public void reconcile() {
        if (closed.get() || waiting.isEmpty() || !sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            responses.execute(() -> {
                try {
                    List<UUID> tokens = new ArrayList<>(waiting.keySet());
                    for (int start = 0; start < tokens.size(); start += config.batchSize()) {
                        submissions.terminalTokens(tokens.subList(start, Math.min(tokens.size(), start + config.batchSize())))
                                .forEach(this::signal);
                    }
                } catch (Exception error) {
                    // Request deadlines still fire if the DB/listener is unavailable.
                    log.warn("Submission waiter recovery read failed: {}", error.getClass().getSimpleName());
                } finally {
                    sweeping.set(false);
                }
            });
        } catch (RejectedExecutionException busy) {
            sweeping.set(false);
        }
    }

    private final class Waiter {
        private final UUID token;
        private final DeferredResult<ResponseEntity<Object>> result;
        private final RequestCorrelation correlation;
        private final Function<CheckpointRow, ResponseEntity<Object>> render;
        private final AtomicBoolean queued = new AtomicBoolean();
        private final AtomicBoolean cleaned = new AtomicBoolean();
        // 0: awaiting checkpoint, 1: building response, 2: completed/expired/disconnected.
        private final AtomicInteger phase = new AtomicInteger();
        private volatile ScheduledFuture<?> checkpointTimer;
        private volatile ScheduledFuture<?> responseTimer;

        private Waiter(UUID token, DeferredResult<ResponseEntity<Object>> result, RequestCorrelation correlation,
                       Function<CheckpointRow, ResponseEntity<Object>> render) {
            this.token = token;
            this.result = result;
            this.correlation = correlation;
            this.render = render;
        }

        private void check() {
            if (phase.get() != 0 || !queued.compareAndSet(false, true)) {
                return;
            }
            try {
                responses.execute(() -> {
                    RequestCorrelation previous = RequestCorrelation.current();
                    Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                    try {
                        if (phase.get() != 0) {
                            return;
                        }
                        RequestCorrelation.set(correlation);
                        MDC.clear();
                        if (correlation != null) {
                            MDC.put("request_id", correlation.requestId());
                            if (correlation.traceId() != null) {
                                MDC.put("trace_id", correlation.traceId());
                            }
                        }
                        CheckpointRow row = submissions.checkpoint(token).orElseThrow();
                        if (!"PREPARING".equals(row.state()) && phase.compareAndSet(0, 1)) {
                            ScheduledFuture<?> checkpoint = checkpointTimer;
                            if (checkpoint != null) checkpoint.cancel(false);
                            ResponseEntity<Object> response = render.apply(row);
                            if (phase.compareAndSet(1, 2)) {
                                try { result.setResult(response); }
                                finally { cleanup(); }
                            }
                        }
                    } catch (Exception error) {
                        fail(error);
                    } finally {
                        if (previous == null) RequestCorrelation.clear();
                        else RequestCorrelation.set(previous);
                        if (previousMdc == null) MDC.clear();
                        else MDC.setContextMap(previousMdc);
                        queued.set(false);
                    }
                });
            } catch (RejectedExecutionException busy) {
                queued.set(false);
                meters.counter("order.checkout.waiter.rejected").increment();
                fail(unavailable());
            }
        }

        private void fail(Object error) {
            if (phase.getAndSet(2) != 2) {
                try { result.setErrorResult(error); }
                finally { cleanup(); }
            }
        }

        private void abandon() {
            phase.set(2);
            cleanup();
        }

        private void cleanup() {
            if (cleaned.compareAndSet(false, true)) {
                cancelTimers();
                waiting.computeIfPresent(token, (key, entries) -> {
                    entries.remove(this);
                    return entries.isEmpty() ? null : entries;
                });
                capacity.release();
            }
        }

        private void cancelTimers() {
            if (checkpointTimer != null) checkpointTimer.cancel(false);
            if (responseTimer != null) responseTimer.cancel(false);
        }
    }

    private static ApiException inProgress() {
        return new ApiException(ErrorCode.SUBMISSION_IN_PROGRESS,
                "Order is still preparing payment; retry with the identical key and body", List.of(), 2, Map.of());
    }

    private static ApiException paymentUnavailable() {
        return new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                "Payment client data is temporarily unavailable; retry", List.of(), 2, Map.of());
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.ORDER_UNAVAILABLE,
                "Order response capacity is temporarily unavailable; retry with the identical key and body",
                List.of(), 2, Map.of());
    }

    @PreDestroy
    public void close() {
        closed.set(true);
        waiting.values().forEach(entries -> entries.forEach(w -> w.fail(inProgress())));
        timers.shutdownNow();
        responses.shutdownNow();
    }
}
