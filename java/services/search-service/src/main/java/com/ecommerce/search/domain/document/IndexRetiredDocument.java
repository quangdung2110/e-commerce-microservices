package com.ecommerce.search.domain.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Marks the moment a physical index stopped being the one an alias serves, so the cleanup job
 * can keep it for the rollback window and then remove it (ADR-022). The index itself carries
 * no such timestamp: its creation date is when its build started, hours before the cutover.
 *
 * <p>{@code targetIndex} is the physical index, {@code finishedAt} the instant it was retired and
 * {@code runId} the run that retired it, when there was one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IndexRetiredDocument(
        String docKind,
        String targetIndex,
        String runId,
        Instant finishedAt) {

    public static final String DOC_KIND = "INDEX_RETIRED";

    public static String docId(String index) {
        return "retired:" + index;
    }
}
