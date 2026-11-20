package com.ecommerce.search.service;

import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.LeaseLockDocument;
import com.ecommerce.search.domain.document.ReindexRunDocument;
import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.repository.es.IndexAdmin;
import com.ecommerce.search.worker.job.FullReindexJob;
import com.ecommerce.search.worker.job.ReindexProgressState;
import com.ecommerce.search.worker.job.ReindexRecovery;
import com.ecommerce.search.worker.productcards.ProductCardProjector;
import com.ecommerce.search.repository.es.ProductCardIndex;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin-facing side of the full reindex: accept a trigger, hand the work to the background
 * job, report status, cancel and roll back.
 *
 * <p>The trigger returns {@code 202} immediately. It does not deduplicate by body; the
 * "one unfinished run at a time" rule is what protects against a double trigger
 * ({@code docs/search/interfaces.md §3.5}), and that rule is the {@code full-reindex} lease, an
 * atomic create in Elasticsearch, rather than a read-then-write. The same entry point serves the
 * automatic start for coalesced control events.
 */
@Service
public class ReindexRunService {

    private final JobStateRepository runs;
    private final FullReindexJob job;
    private final ReindexPreconditions preconditions;
    private final ReindexRecovery recovery;
    private final IndexAdmin indexAdmin;
    private final SearchProperties properties;
    private final SearchMetrics metrics;
    private final ProductCardProjector productCards;

    public ReindexRunService(JobStateRepository runs, FullReindexJob job, ReindexPreconditions preconditions,
                             ReindexRecovery recovery, IndexAdmin indexAdmin, SearchProperties properties,
                             SearchMetrics metrics, ProductCardProjector productCards) {
        this.runs = runs;
        this.job = job;
        this.preconditions = preconditions;
        this.recovery = recovery;
        this.indexAdmin = indexAdmin;
        this.properties = properties;
        this.metrics = metrics;
        this.productCards = productCards;
    }

    /** @param actor taken from the trusted gateway identity, never from the request body */
    public SearchDtos.ReindexRun trigger(String reason, String actor) throws IOException {
        // A run whose owner died is dealt with first, so it neither blocks this trigger forever
        // nor gets a second run started on top of it.
        ReindexRecovery.Outcome outcome = recovery.settleAbandoned();
        if (outcome == ReindexRecovery.Outcome.ALIVE || outcome == ReindexRecovery.Outcome.RESUMED) {
            throw new SearchException(ErrorCode.REINDEX_ALREADY_RUNNING,
                    outcome == ReindexRecovery.Outcome.RESUMED
                            ? "an abandoned reindex run was just resumed; retry after it finishes"
                            : "a reindex run is already in progress; retry after it finishes");
        }
        preconditions.check();

        String runId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        Optional<LeaseLockDocument> lease = runs.acquireLease(LeaseLockDocument.REINDEX_LOCK, runId, startedAt,
                startedAt.plus(properties.lifecycle().leaseDuration()));
        if (lease.isEmpty() || runs.findActiveRun().isPresent()) {
            lease.ifPresent(held -> release(held));
            throw new SearchException(ErrorCode.REINDEX_ALREADY_RUNNING,
                    "a reindex run is already in progress; retry after it finishes");
        }

        runs.saveRun(new ReindexRunDocument(
                ReindexRunDocument.DOC_KIND, runId, "STARTED", null, reason, actor,
                startedAt, null, startedAt, null, null, null, Map.of(), List.of(), Map.of()));

        job.run(runId, reason, actor, lease.get(), null);
        return toDto(runs.findRun(runId).orElseThrow(() -> SearchException.reindexRunNotFound(runId)));
    }

    public SearchDtos.ReindexRun status(String runId) throws IOException {
        return toDto(runs.findRun(runId).orElseThrow(() -> SearchException.reindexRunNotFound(runId)));
    }

    public SearchDtos.ReindexRunList list(Optional<String> status, int limit) throws IOException {
        return new SearchDtos.ReindexRunList(runs.listRuns(status, limit).stream().map(this::toDto).toList());
    }

    /**
     * Asks a run to stop. The owner notices at its next page and ends the run as
     * {@code CANCELLED}, deleting what it built; when no owner is left the request is carried
     * out here. A run at the verify-and-swap step can no longer be cancelled.
     */
    public SearchDtos.ReindexRun cancel(String runId, String actor) throws IOException {
        ReindexRunDocument run = runs.findRun(runId).orElseThrow(() -> SearchException.reindexRunNotFound(runId));
        if (!run.isCancellable()) {
            throw new SearchException(ErrorCode.REINDEX_RUN_NOT_CANCELLABLE, run.isTerminal()
                    ? "run " + runId + " already finished as " + run.status()
                    : "run " + runId + " is switching the aliases and can no longer be cancelled");
        }
        runs.requestCancel(runId, actor, Instant.now());

        Optional<LeaseLockDocument> lease = runs.readLease(LeaseLockDocument.REINDEX_LOCK);
        boolean ownerAlive = lease.isPresent() && !lease.get().expired(Instant.now());
        if (!ownerAlive) {
            recovery.cancelAbandoned(run);
        }
        return toDto(runs.findRun(runId).orElseThrow(() -> SearchException.reindexRunNotFound(runId)));
    }

    /**
     * Puts the indices a completed run replaced back behind the aliases in one atomic swap.
     * Only the latest cutover can be undone, and only while the previous indices still exist
     * (the cleanup job removes them after the retention window). Anything written to the new
     * index since the cutover is not in the old one: run a new reindex to converge.
     */
    public SearchDtos.ReindexRun rollback(String runId, String actor) throws IOException {
        ReindexRunDocument run = runs.findRun(runId).orElseThrow(() -> SearchException.reindexRunNotFound(runId));
        if (!"COMPLETED".equals(run.status())) {
            throw unavailable("only a COMPLETED run can be rolled back; run " + runId + " is " + run.status());
        }
        ReindexProgressState state = ReindexProgressState.restore(run);
        if (state.previousProductsIndex() == null || state.previousCategoriesIndex() == null) {
            throw unavailable("run " + runId + " replaced no index (it was the first build), so there is nothing to return to");
        }

        Instant now = Instant.now();
        Optional<LeaseLockDocument> lease = runs.acquireLease(LeaseLockDocument.REINDEX_LOCK, "rollback:" + runId,
                now, now.plus(properties.lifecycle().leaseDuration()));
        if (lease.isEmpty() || runs.findActiveRun().isPresent()) {
            lease.ifPresent(held -> release(held));
            throw unavailable("another reindex run or rollback is in progress");
        }
        try {
            requireStillTheLatestCutover(state);
            requireExists(state.previousProductsIndex());
            requireExists(state.previousCategoriesIndex());

            Map<String, List<String>> targets = new LinkedHashMap<>();
            targets.put(state.previousProductsIndex(),
                    List.of(properties.aliases().products(), properties.aliases().productsWrite()));
            targets.put(state.previousCategoriesIndex(),
                    List.of(properties.aliases().categories(), properties.aliases().categoriesWrite()));
            Optional<String> previousCards = indexAdmin.resolveAlias(ProductCardIndex.ALIAS);
            try (var prepared = productCards.prepareForCutover(state.previousProductsIndex(), () -> {
                try {
                    if (!runs.renewLease(lease.get(), Instant.now(),
                            Instant.now().plus(properties.lifecycle().leaseDuration())))
                        throw unavailable("rollback lost its lease");
                } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            })) {
                targets.put(prepared.index(), List.of(ProductCardIndex.ALIAS));
                requireStillTheLatestCutover(state);
                prepared.checkOwnership();
                indexAdmin.swapAliasesAtomically(targets);
                prepared.published();
            }
            if (previousCards.isPresent()) runs.markIndexRetired(previousCards.get(), runId, Instant.now());

            runs.clearIndexRetired(state.previousProductsIndex());
            runs.clearIndexRetired(state.previousCategoriesIndex());
            runs.markIndexRetired(state.productsIndex(), runId, now);
            runs.markIndexRetired(state.categoriesIndex(), runId, now);

            Map<String, Object> checkpoint = new LinkedHashMap<>(run.checkpointOrEmpty());
            checkpoint.put("rolled_back_at", now);
            checkpoint.put("rolled_back_by", actor);
            runs.saveRun(new ReindexRunDocument(run.docKind(), run.runId(), "ROLLED_BACK", null, run.reason(),
                    run.actor(), run.startedAt(), run.finishedAt(), now, null, null, run.targetIndex(),
                    run.progress(), run.replayFromOffsets(), checkpoint));
            metrics.reindexState("ROLLED_BACK");
            metrics.reindexLifecycle("rolled_back");
        } finally {
            release(lease.get());
        }
        return toDto(runs.findRun(runId).orElseThrow(() -> SearchException.reindexRunNotFound(runId)));
    }

    private void requireStillTheLatestCutover(ReindexProgressState state) throws IOException {
        boolean productsServe = indexAdmin.resolveAlias(properties.aliases().products())
                .filter(state.productsIndex()::equals).isPresent();
        boolean categoriesServe = indexAdmin.resolveAlias(properties.aliases().categories())
                .filter(state.categoriesIndex()::equals).isPresent();
        if (!productsServe || !categoriesServe) {
            throw unavailable("the aliases no longer point at the indices this run built; "
                    + "only the latest cutover can be rolled back");
        }
    }

    private void requireExists(String index) throws IOException {
        if (!indexAdmin.indexExists(index)) {
            throw unavailable("the previous index " + index + " was removed after the retention window");
        }
    }

    private static SearchException unavailable(String message) {
        return new SearchException(ErrorCode.REINDEX_ROLLBACK_UNAVAILABLE, message);
    }

    private void release(LeaseLockDocument lease) {
        try {
            runs.releaseLease(lease);
        } catch (IOException ignored) {
            // The lease expires on its own.
        }
    }

    SearchDtos.ReindexRun toDto(ReindexRunDocument run) {
        Map<String, Object> progress = run.progress() == null ? Map.of() : run.progress();
        SearchDtos.ReindexFailure failure = run.failureCode() == null
                ? null
                : new SearchDtos.ReindexFailure(run.failureCode(), String.valueOf(run.failureMessage()));

        return new SearchDtos.ReindexRun(
                run.runId(),
                run.status(),
                run.phase(),
                run.reason(),
                run.actor(),
                new SearchDtos.ReindexProgress(
                        asLong(progress.get("categories_exported")),
                        asLong(progress.get("products_exported")),
                        asLong(progress.get("stock_snapshots_imported")),
                        asLong(progress.get("rating_aggregates_imported")),
                        progress.get("catch_up_lag") == null ? null : asLong(progress.get("catch_up_lag"))),
                "/api/v2/search/reindex-runs/" + run.runId(),
                run.startedAt(),
                run.finishedAt(),
                failure);
    }

    private static long asLong(Object raw) {
        return raw instanceof Number number ? number.longValue() : 0L;
    }
}
