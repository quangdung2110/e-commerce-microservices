package com.ecommerce.order.workflow;

import com.ecommerce.order.workflow.ActivityResults.Outcome;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Shared deterministic plumbing: message dedup by stable ID with a bounded recent set
 * carried across Continue-As-New, durable activity invocation (an exhausted activity
 * retry burst sleeps and retries instead of failing the workflow) and unknown-outcome
 * replay loops with capped backoff.
 */
abstract class MessageQueueWorkflow {

    static final int RECENT_IDS = 512;
    static final int HISTORY_LIMIT = 8_000;
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    protected final Logger log = Workflow.getLogger(getClass());
    protected final OrderActivities activities = Workflow.newActivityStub(OrderActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    protected final Deque<WorkflowMessage> queue = new ArrayDeque<>();
    protected final LinkedHashSet<String> seen = new LinkedHashSet<>();
    protected Map<String, String> state = new HashMap<>();

    protected void restore(WorkflowInput input) {
        if (input.recentMessageIds() != null) {
            seen.addAll(input.recentMessageIds());
        }
        if (input.pending() != null) {
            queue.addAll(input.pending());
        }
        if (input.state() != null) {
            state = new HashMap<>(input.state());
        }
    }

    /** Signal handler body: duplicate message IDs are dropped before any activity. */
    protected void accept(WorkflowMessage message) {
        if (message == null || message.messageId() == null || seen.contains(message.messageId())) {
            return;
        }
        seen.add(message.messageId());
        while (seen.size() > RECENT_IDS) {
            seen.remove(seen.iterator().next());
        }
        queue.add(message);
    }

    protected boolean continueAsNewSuggested() {
        return Workflow.getInfo().getHistoryLength() > HISTORY_LIMIT;
    }

    protected WorkflowInput carry(Map<String, String> args) {
        return new WorkflowInput(args, new ArrayList<>(seen), new ArrayList<>(queue), new HashMap<>(state));
    }

    /** Runs an activity; an exhausted retry burst becomes a durable sleep-and-retry. */
    protected <T> T durable(Functions.Func<T> call) {
        int attempt = 0;
        while (true) {
            try {
                return call.apply();
            } catch (ActivityFailure failure) {
                attempt++;
                log.warn("Activity burst failed (attempt {}): {}", attempt, failure.getMessage());
                Workflow.sleep(backoff(attempt));
            }
        }
    }

    protected void durableVoid(Functions.Proc call) {
        durable(() -> {
            call.apply();
            return Boolean.TRUE;
        });
    }

    /** Replays the same remote command until its outcome is known (never assumes failure). */
    protected Outcome untilKnown(Functions.Func<Outcome> call) {
        int attempt = 0;
        while (true) {
            Outcome outcome = durable(call);
            if (!outcome.isUnknown()) {
                return outcome;
            }
            attempt++;
            Workflow.sleep(backoff(attempt));
        }
    }

    /** Bounded replay; returns the last (possibly unknown) outcome after maxAttempts. */
    protected Outcome boundedReplay(Functions.Func<Outcome> call, int maxAttempts) {
        Outcome outcome = durable(call);
        for (int attempt = 1; outcome.isUnknown() && attempt < maxAttempts; attempt++) {
            Workflow.sleep(backoff(attempt));
            outcome = durable(call);
        }
        return outcome;
    }

    protected static Duration backoff(int attempt) {
        long seconds = Math.min(MAX_BACKOFF.toSeconds(), (long) (5 * Math.pow(2, Math.min(attempt - 1, 10))));
        return Duration.ofSeconds(seconds);
    }

    protected List<WorkflowMessage> drain() {
        List<WorkflowMessage> drained = new ArrayList<>(queue);
        queue.clear();
        return drained;
    }
}
