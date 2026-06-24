package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * Response DTO for {@code POST /internal/v1/search/reindex} (HTTP 202 Accepted).
 *
 * <p>Returned immediately after triggering an async full reindex job.
 * The client polls {@code statusUrl} to track progress.
 */
@Builder
public record ReindexTriggerResponse(
    @JsonProperty("run_id") String runId,
    String status,
    @JsonProperty("started_at") String startedAt,
    @JsonProperty("estimated_duration_minutes") Integer estimatedDurationMinutes,
    @JsonProperty("status_url") String statusUrl
) {}
