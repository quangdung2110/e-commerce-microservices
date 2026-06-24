package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request DTO for {@code POST /internal/v1/search/reindex}.
 *
 * @param reason      the human-readable reason for triggering a reindex
 * @param triggeredBy who or what triggered this reindex (e.g. "admin", "scheduled", "system")
 */
public record ReindexRequest(
    @JsonProperty("reason") String reason,
    @JsonProperty("triggered_by") String triggeredBy
) {}
