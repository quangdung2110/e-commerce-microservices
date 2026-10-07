package com.ecommerce.search.domain.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * State of one full reindex run, stored in the internal {@code search_jobs} index.
 *
 * <p>Exactly one run may be unfinished at a time; a second trigger is rejected with
 * {@code REINDEX_ALREADY_RUNNING}, which is also what makes the trigger idempotent
 * ({@code docs/search/interfaces.md §3.5}).
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

    /** Terminal states; anything else means a run is still holding the singleton slot. */
    public static final List<String> TERMINAL_STATUSES =
            List.of("COMPLETED", "FAILED", "ROLLED_BACK", "SUPERSEDED");

    public boolean isTerminal() {
        return status != null && TERMINAL_STATUSES.contains(status);
    }
}
