package com.ecommerce.search.service;

import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.domain.document.ReindexRunDocument;
import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.worker.job.FullReindexJob;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-facing side of the full reindex: accept a trigger, hand the work to the background
 * job and report status.
 *
 * <p>The trigger returns {@code 202} immediately. It does not deduplicate by body; the
 * "one unfinished run at a time" rule is what protects against a double trigger
 * ({@code docs/search/interfaces.md §3.5}).
 */
@Service
public class ReindexRunService {

    private final JobStateRepository runs;
    private final FullReindexJob job;

    public ReindexRunService(JobStateRepository runs, FullReindexJob job) {
        this.runs = runs;
        this.job = job;
    }

    /** @param actor taken from the trusted gateway identity, never from the request body */
    public SearchDtos.ReindexRun trigger(String reason, String actor) throws IOException {
        if (runs.findActiveRun().isPresent()) {
            throw new SearchException(ErrorCode.REINDEX_ALREADY_RUNNING,
                    "a reindex run is already in progress; retry after it finishes");
        }
        String runId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();

        runs.saveRun(new ReindexRunDocument(
                ReindexRunDocument.DOC_KIND, runId, "STARTED", null, reason, actor,
                startedAt, null, startedAt, null, null, null, Map.of(), java.util.List.of(), Map.of()));

        job.run(runId, reason, actor);
        return toDto(runs.findRun(runId).orElseThrow(() -> SearchException.reindexRunNotFound(runId)));
    }

    public SearchDtos.ReindexRun status(String runId) throws IOException {
        return toDto(runs.findRun(runId).orElseThrow(() -> SearchException.reindexRunNotFound(runId)));
    }

    private SearchDtos.ReindexRun toDto(ReindexRunDocument run) {
        Map<String, Object> progress = run.progress() == null ? Map.of() : run.progress();
        SearchDtos.ReindexFailure failure = run.failureCode() == null
                ? null
                : new SearchDtos.ReindexFailure(run.failureCode(), String.valueOf(run.failureMessage()));

        return new SearchDtos.ReindexRun(
                run.runId(),
                run.status(),
                run.phase(),
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
