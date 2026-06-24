package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.Map;

/**
 * Response DTO for {@code GET /internal/v1/search/reindex/{runId}}.
 *
 * <p>Reflects the current phase and progress of a full reindex job.
 * Status transitions: BUILDING → CATCHING_UP → CUTOVER → COMPLETED,
 * or FAILED / ROLLED_BACK on errors.
 */
@Builder
public record ReindexStatusResponse(
    @JsonProperty("run_id") String runId,
    String status,
    @JsonProperty("current_phase") String currentPhase,
    Map<String, Object> progress,
    @JsonProperty("started_at") String startedAt,
    @JsonProperty("finished_at") String finishedAt,
    @JsonProperty("error_message") String errorMessage
) {}
