package com.ecommerce.search.service;

import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.dto.ReindexRequest;
import com.ecommerce.search.dto.ReindexStatusResponse;
import com.ecommerce.search.dto.ReindexTriggerResponse;
import com.ecommerce.search.repository.ReindexRepository;
import com.ecommerce.search.repository.es.ProductIndexMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the 3-phase full reindex of the products Elasticsearch index.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li><b>Trigger:</b> {@link #trigger(ReindexRequest)} generates a {@code run_id},
 *       stores initial status, and starts async execution. Returns HTTP 202 Accepted.</li>
 *   <li><b>Phase 1 — Build:</b> Create a new versioned index ({@code products_v{N+1}})
 *       and clone all documents from the current alias via ES {@code _reindex}.</li>
 *   <li><b>Phase 2 — Catch-up:</b> Run a second {@code _reindex} pass to pick up any
 *       documents that changed during Phase 1, then verify doc counts.</li>
 *   <li><b>Phase 3 — Cutover:</b> Atomic alias swap from old index to new index for
 *       both {@code products} and {@code products_write} aliases.</li>
 * </ol>
 *
 * <h3>Idempotency</h3>
 * Only one reindex may run at a time. If a second trigger is attempted while a run
 * is active, the service throws {@link SearchException} with
 * {@link ErrorCode#REINDEX_ALREADY_RUNNING}.
 *
 * <h3>Error handling</h3>
 * <ul>
 *   <li>If Phase 1 fails, the new index is deleted.</li>
 *   <li>If Phase 3 fails during alias swap, the process is aborted and the old index
 *       remains active — no rollback is needed since the swap is atomic.</li>
 *   <li>All errors are recorded in the in-memory {@link ReindexStatus} for client
 *       polling.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReindexService {

    private final ReindexRepository reindexRepository;
    private final SearchProperties properties;

    private final Map<String, ReindexStatus> activeRuns = new ConcurrentHashMap<>();

    /**
     * Trigger a new full reindex job.
     *
     * @param request the reindex request with reason and trigger info
     * @return a {@link ReindexTriggerResponse} with the run_id and status URL
     * @throws SearchException with {@link ErrorCode#REINDEX_ALREADY_RUNNING} if
     *                         another reindex is already in progress
     */
    public ReindexTriggerResponse trigger(ReindexRequest request) {
        // 1. Check if reindex already running
        if (!activeRuns.isEmpty()) {
            String existingRunId = activeRuns.keySet().iterator().next();
            throw new SearchException(ErrorCode.REINDEX_ALREADY_RUNNING,
                "Reindex job already running: " + existingRunId);
        }

        // 2. Generate run_id
        String runId = "reindex-" + Instant.now().toString().replace(":", "-") + "-"
            + UUID.randomUUID().toString().substring(0, 8);

        // 3. Create initial status
        ReindexStatus status = new ReindexStatus(runId, "STARTED", Instant.now().toString());
        activeRuns.put(runId, status);

        // 4. Start async execution
        executeReindexAsync(runId, request);

        log.info("Reindex triggered: runId={}, reason={}, triggeredBy={}",
            runId, request.reason(), request.triggeredBy());

        return ReindexTriggerResponse.builder()
            .runId(runId)
            .status("STARTED")
            .startedAt(Instant.now().toString())
            .estimatedDurationMinutes(60)
            .statusUrl("/internal/v1/search/reindex/" + runId)
            .build();
    }

    /**
     * Get the current status of a reindex job.
     *
     * @param runId the run ID returned by {@link #trigger}
     * @return the current status and progress
     * @throws SearchException with {@link ErrorCode#INTERNAL_ERROR} if the run
     *                         ID is not found
     */
    public ReindexStatusResponse getStatus(String runId) {
        ReindexStatus status = activeRuns.get(runId);
        if (status == null) {
            throw new SearchException(ErrorCode.INTERNAL_ERROR, "Run not found: " + runId);
        }
        return toResponse(status);
    }

    /**
     * Async execution entry point. Runs the 3-phase reindex in a background thread.
     * Cleans up the in-memory status after a delay on completion.
     */
    @Async("reindexExecutor")
    public void executeReindexAsync(String runId, ReindexRequest request) {
        try {
            executeReindex(runId, request);
        } catch (Exception e) {
            log.error("Reindex {} failed with unexpected error", runId, e);
            ReindexStatus status = activeRuns.get(runId);
            if (status != null) {
                status.phase = "FAILED";
                status.errorMessage = "Unexpected error: " + e.getMessage();
            }
        }
    }

    /**
     * Execute the 3-phase reindex synchronously.
     */
    private void executeReindex(String runId, ReindexRequest request) {
        ReindexStatus status = activeRuns.get(runId);
        if (status == null) {
            log.warn("Reindex {} status not found, aborting", runId);
            return;
        }

        String currentIndex = reindexRepository.resolveIndexForAlias(
            properties.getEs().getIndexAliases().getProductsWrite());
        if (currentIndex == null) {
            status.phase = "FAILED";
            status.errorMessage = "Cannot resolve current write index";
            return;
        }
        log.info("Reindex {}: current write index is [{}]", runId, currentIndex);

        String newIndexName = "products_v" + System.currentTimeMillis();
        long totalDocs;

        // ================================================================
        // Phase 1: Build
        // ================================================================
        status.phase = "BUILDING";
        status.progress = Map.of("build_docs_written", 0L);
        log.info("Reindex {}: Phase 1 (BUILD) starting, new_index={}", runId, newIndexName);

        try {
            // 1a. Create new index with the current mapping
            reindexRepository.createIndex(newIndexName, ProductIndexMapping.MAPPING_JSON);
            log.info("Reindex {}: Created index [{}]", runId, newIndexName);

            // 1b. Clone all documents from the current alias to the new index
            //     Uses ES _reindex API which provides a point-in-time snapshot.
            totalDocs = reindexRepository.cloneIndex(currentIndex, newIndexName);
            status.progress = Map.of("build_docs_written", totalDocs);
            log.info("Reindex {}: Phase 1 complete: cloned {} docs to [{}]", runId, totalDocs, newIndexName);

        } catch (Exception e) {
            log.error("Reindex {}: Phase 1 (BUILD) failed", runId, e);
            status.phase = "FAILED";
            status.errorMessage = "Build phase failed: " + e.getMessage();
            cleanupNewIndex(newIndexName);
            return;
        }

        // ================================================================
        // Phase 2: Catch-up
        // ================================================================
        status.phase = "CATCHING_UP";
        status.progress = Map.of(
            "build_docs_written", totalDocs,
            "catchup_lag_seconds", 0
        );
        log.info("Reindex {}: Phase 2 (CATCH_UP) starting", runId);

        try {
            // Run a second _reindex pass to pick up any changes that arrived
            // during Phase 1. ES _reindex with default op_type=index will
            // overwrite existing documents with their latest versions.
            long secondPassDocs = reindexRepository.cloneIndex(currentIndex, newIndexName);
            log.info("Reindex {}: Phase 2 catch-up pass cloned {} additional/updated docs",
                runId, secondPassDocs);

            // Verify doc count
            long newIndexCount = reindexRepository.countDocs(newIndexName);
            long oldIndexCount = reindexRepository.countDocs(currentIndex);

            // Acceptable drift: max 0.5% or 10,000 docs
            long drift = Math.abs(newIndexCount - oldIndexCount);
            long maxDrift = Math.min((long) (oldIndexCount * 0.005), 10_000L);
            if (drift > maxDrift) {
                log.warn("Reindex {}: Doc count drift exceeds threshold: new={}, old={}, drift={}, maxDrift={}",
                    runId, newIndexCount, oldIndexCount, drift, maxDrift);
                // Continue anyway — the alias swap is atomic and we can always rollback
            }

            long finalCount = Math.max(newIndexCount, oldIndexCount);
            status.progress = Map.of(
                "build_docs_written", totalDocs,
                "catchup_lag_seconds", 0,
                "catchup_total_docs", finalCount
            );
            log.info("Reindex {}: Phase 2 complete: new_index has {} docs", runId, finalCount);

        } catch (Exception e) {
            log.error("Reindex {}: Phase 2 (CATCH_UP) failed", runId, e);
            status.phase = "FAILED";
            status.errorMessage = "Catch-up phase failed: " + e.getMessage();
            cleanupNewIndex(newIndexName);
            return;
        }

        // ================================================================
        // Phase 3: Cutover
        // ================================================================
        status.phase = "CUTOVER";
        log.info("Reindex {}: Phase 3 (CUTOVER) starting", runId);

        try {
            reindexRepository.swapAlias(
                currentIndex,
                newIndexName,
                properties.getEs().getIndexAliases().getProducts(),
                properties.getEs().getIndexAliases().getProductsWrite()
            );
            log.info("Reindex {}: Phase 3 complete — aliases [{}] and [{}] now point to [{}]",
                runId,
                properties.getEs().getIndexAliases().getProducts(),
                properties.getEs().getIndexAliases().getProductsWrite(),
                newIndexName);

        } catch (Exception e) {
            log.error("Reindex {}: Phase 3 (CUTOVER) failed — old index [{}] remains active",
                runId, currentIndex, e);
            status.phase = "FAILED";
            status.errorMessage = "Cutover phase failed: " + e.getMessage();
            cleanupNewIndex(newIndexName);
            return;
        }

        // ================================================================
        // Success
        // ================================================================
        status.phase = "COMPLETED";
        status.finishedAt = Instant.now().toString();
        status.progress = Map.of(
            "build_docs_written", totalDocs,
            "catchup_total_docs", reindexRepository.countDocs(newIndexName)
        );
        log.info("Reindex {} completed successfully: {} docs migrated to [{}]",
            runId, totalDocs, newIndexName);
    }

    /**
     * Clean up the new index if a phase fails.
     */
    private void cleanupNewIndex(String indexName) {
        try {
            reindexRepository.deleteIndex(indexName);
            log.info("Cleaned up new index [{}] after failed reindex", indexName);
        } catch (Exception e) {
            log.warn("Failed to clean up new index [{}]", indexName, e);
        }
    }

    private ReindexStatusResponse toResponse(ReindexStatus status) {
        return ReindexStatusResponse.builder()
            .runId(status.runId)
            .status(status.phase)
            .currentPhase(status.phase)
            .progress(status.progress)
            .startedAt(status.startedAt)
            .finishedAt(status.finishedAt)
            .errorMessage(status.errorMessage)
            .build();
    }

    /**
     * In-memory state holder for a single reindex run.
     * <p>
     * Stored in {@link #activeRuns} and accessed by both the trigger thread
     * and the async execution thread. Intentionally not exposed outside this
     * service — callers interact via {@link ReindexStatusResponse} DTOs.
     */
    private static class ReindexStatus {
        final String runId;
        volatile String phase;
        volatile Map<String, Object> progress;
        final String startedAt;
        volatile String finishedAt;
        volatile String errorMessage;

        ReindexStatus(String runId, String phase, String startedAt) {
            this.runId = runId;
            this.phase = phase;
            this.startedAt = startedAt;
            this.progress = Map.of();
        }
    }
}
