package com.ecommerce.search.worker.job;

import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.LeaseLockDocument;
import com.ecommerce.search.domain.document.ReindexRunDocument;
import com.ecommerce.search.repository.JobStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides what happens to an unfinished run whose owner is gone (ADR-022).
 *
 * <p>A run is alive while the {@code full-reindex} lease it owns is renewed. When the lease
 * has expired the process died or hung, and exactly one replica wins the right to deal with
 * the run: it resumes it from the last saved stage, up to {@code max-resume-attempts} times,
 * and fails it as {@code RUN_ABANDONED} after that, deleting the half-built indices and freeing
 * the singleton slot so the next trigger is no longer refused.
 */
@Component
public class ReindexRecovery {

    private static final Logger log = LoggerFactory.getLogger(ReindexRecovery.class);

    /** What {@link #settleAbandoned} found. */
    public enum Outcome {
        /** No unfinished run. */
        NOTHING_ACTIVE,
        /** An unfinished run whose owner is still renewing its lease. */
        ALIVE,
        /** An abandoned run was handed back to the job. */
        RESUMED,
        /** An abandoned run was failed and its indices deleted. */
        ABANDONED
    }

    private final JobStateRepository runs;
    private final FullReindexJob job;
    private final SearchProperties properties;
    private final SearchMetrics metrics;

    public ReindexRecovery(JobStateRepository runs, FullReindexJob job, SearchProperties properties,
                           SearchMetrics metrics) {
        this.runs = runs;
        this.job = job;
        this.properties = properties;
        this.metrics = metrics;
    }

    public Outcome settleAbandoned() throws IOException {
        Optional<ReindexRunDocument> active = runs.findActiveRun();
        if (active.isEmpty()) {
            return Outcome.NOTHING_ACTIVE;
        }
        ReindexRunDocument run = active.get();
        Instant now = Instant.now();

        if (ownerIsAlive(run, now)) {
            return Outcome.ALIVE;
        }
        Optional<LeaseLockDocument> lease = runs.acquireLease(LeaseLockDocument.REINDEX_LOCK, run.runId(),
                now, now.plus(properties.lifecycle().leaseDuration()));
        if (lease.isEmpty()) {
            // Another replica won the right to handle it a moment ago.
            return Outcome.ALIVE;
        }

        ReindexProgressState state = ReindexProgressState.restore(run);
        int attempts = state.resumeAttempts();
        if (attempts >= properties.lifecycle().maxResumeAttempts()) {
            abandon(run, state, lease.get());
            return Outcome.ABANDONED;
        }

        state.setResumeAttempts(attempts + 1);
        ReindexRunDocument resumable = withCheckpoint(run, state);
        runs.saveRun(resumable);
        log.warn("reindex run {} has no live owner; resuming at stage {} (attempt {} of {})",
                run.runId(), state.stage(), attempts + 1, properties.lifecycle().maxResumeAttempts());
        metrics.reindexLifecycle("resumed");
        job.run(run.runId(), run.reason(), run.actor(), lease.get(), resumable);
        return Outcome.RESUMED;
    }

    /**
     * Finishes a run nobody is working on as cancelled, because an administrator asked and
     * there is no owner left to notice. The caller has verified that the owner is gone.
     */
    public void cancelAbandoned(ReindexRunDocument run) throws IOException {
        Instant now = Instant.now();
        Optional<LeaseLockDocument> lease = runs.acquireLease(LeaseLockDocument.REINDEX_LOCK, run.runId(),
                now, now.plus(properties.lifecycle().leaseDuration()));
        if (lease.isEmpty()) {
            return;
        }
        ReindexProgressState state = ReindexProgressState.restore(run);
        job.discard(state);
        runs.saveRun(finished(run, state, "CANCELLED", "RUN_CANCELLED", "cancelled by an administrator"));
        runs.clearCancel(run.runId());
        runs.releaseLease(lease.get());
        metrics.reindexState("CANCELLED");
        metrics.reindexLifecycle("cancelled");
    }

    private void abandon(ReindexRunDocument run, ReindexProgressState state, LeaseLockDocument lease)
            throws IOException {
        log.error("reindex run {} was resumed {} times and never finished; failing it as abandoned",
                run.runId(), state.resumeAttempts());
        job.discard(state);
        runs.saveRun(finished(run, state, "FAILED", "RUN_ABANDONED",
                "no process kept the run alive after " + state.resumeAttempts() + " resume attempts"));
        runs.releaseLease(lease);
        metrics.reindexState("FAILED");
        metrics.reindexLifecycle("abandoned");
    }

    private boolean ownerIsAlive(ReindexRunDocument run, Instant now) throws IOException {
        Optional<LeaseLockDocument> lease = runs.readLease(LeaseLockDocument.REINDEX_LOCK);
        return lease.isPresent() && !lease.get().expired(now);
    }

    private static ReindexRunDocument withCheckpoint(ReindexRunDocument run, ReindexProgressState state) {
        Map<String, Object> checkpoint = new LinkedHashMap<>(run.checkpointOrEmpty());
        checkpoint.putAll(state.checkpointAsMap());
        return new ReindexRunDocument(run.docKind(), run.runId(), run.status(), run.phase(), run.reason(),
                run.actor(), run.startedAt(), run.finishedAt(), Instant.now(), run.failureCode(),
                run.failureMessage(), run.targetIndex(), run.progress(), run.replayFromOffsets(), checkpoint);
    }

    private static ReindexRunDocument finished(ReindexRunDocument run, ReindexProgressState state, String status,
                                               String code, String message) {
        Instant now = Instant.now();
        return new ReindexRunDocument(run.docKind(), run.runId(), status, null, run.reason(), run.actor(),
                run.startedAt(), now, now, code, message, run.targetIndex(), run.progress(),
                run.replayFromOffsets() == null ? List.of() : run.replayFromOffsets(), state.checkpointAsMap());
    }
}
