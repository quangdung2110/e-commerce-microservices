package com.ecommerce.search.domain.document;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * State of one full reindex run, stored in the internal {@code search_jobs} index.
 *
 * <p>Exactly one run may be unfinished at a time; a second trigger is rejected with
 * {@code REINDEX_ALREADY_RUNNING}, which is also what makes the trigger idempotent
 * ({@code docs/search/interfaces.md §3.5}). Which process is still working on an unfinished
 * run is answered by the {@code full-reindex} lease, not by this document.
 *
 * <p>{@code checkpoint} is a free-form object (the mapping keeps it unindexed), so what a
 * resume needs lives there without a change to the canonical mapping:
 * {@code stage}, the two target indices, per-source cursors, the Inventory and Review sessions,
 * {@code resume_attempts}, {@code cancel_requested}, and after cutover the indices it replaced.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReindexRunDocument(
        String docKind,
        String runId,
        String status,
        String phase,
        String reason,
        String actor,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt,
        String failureCode,
        String failureMessage,
        String targetIndex,
        Map<String, Object> progress,
        List<Map<String, Object>> replayFromOffsets,
        Map<String, Object> checkpoint) {

    public static final String DOC_KIND = "REINDEX_RUN";

    public static final String CANCELLED = "CANCELLED";

    /** Terminal states; anything else means a run is still holding the singleton slot. */
    public static final List<String> TERMINAL_STATUSES =
            List.of("COMPLETED", "FAILED", "ROLLED_BACK", "SUPERSEDED", CANCELLED);

    /**
     * {@code @JsonIgnore} on both predicates is not decoration: Jackson reads {@code isXxx()} as a
     * property, and the {@code search_jobs} mapping is {@code dynamic: strict}, so a stray
     * {@code terminal} field made every write of a run fail.
     */
    @JsonIgnore
    public boolean isTerminal() {
        return status != null && TERMINAL_STATUSES.contains(status);
    }

    /** A run can be cancelled until it reaches the short verify-and-swap step. */
    @JsonIgnore
    public boolean isCancellable() {
        return !isTerminal() && !"CUTOVER".equals(status);
    }

    public Map<String, Object> checkpointOrEmpty() {
        return checkpoint == null ? Map.of() : checkpoint;
    }
}
