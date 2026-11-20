package com.ecommerce.search.domain.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * An administrator's request to cancel a run, kept as a document of its own because the running
 * job replaces the whole run document at every checkpoint ({@code cancel:<run_id>}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReindexCancelDocument(String docKind, String runId, String actor, Instant updatedAt) {

    public static final String DOC_KIND = "REINDEX_CANCEL";

    public static String docId(String runId) {
        return "cancel:" + runId;
    }
}
