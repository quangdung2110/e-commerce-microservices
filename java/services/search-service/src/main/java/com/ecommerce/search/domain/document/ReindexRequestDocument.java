package com.ecommerce.search.domain.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * The coalesced, durable form of every {@code catalog-reindex-requested} event not yet
 * satisfied by a completed run (ADR-022).
 *
 * <p>There is exactly one such document, {@code reindex-request:pending}. {@code startedAt} is
 * when the first request of the burst arrived and {@code updatedAt} when the latest did; the
 * debounce measures from {@code updatedAt}. {@code checkpoint.roots} maps each root category to
 * the highest {@code category_revision} requested, which is the coalescing key, and
 * {@code checkpoint.last_attempt_at} is when an automatic run was last started for it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReindexRequestDocument(
        String docKind,
        String reason,
        Instant startedAt,
        Instant updatedAt,
        Map<String, Object> checkpoint) {

    public static final String DOC_KIND = "REINDEX_REQUEST";
    public static final String DOC_ID = "reindex-request:pending";
}
