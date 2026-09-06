package com.ecommerce.search.domain.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A distributed lease lock held in the internal {@code search_jobs} index
 * ({@code docs/search/data.md §10}).
 *
 * <p>The owner renews the lease while it runs and only the owner may release it. Every
 * alias swap re-checks the fencing token, so an owner that died and lost its lease cannot
 * cut over after a later run has taken the lock.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LeaseLockDocument(
        String docKind,
        String lockName,
        String ownerRunId,
        Instant leaseExpiresAt,
        Long fencingToken,
        Instant updatedAt) {

    public static final String DOC_KIND = "LOCK";
    public static final String SUGGESTION_REBUILD_LOCK = "suggestion-rebuild";
    public static final String REINDEX_LOCK = "full-reindex";
    public static final String PRODUCT_COUNT_LOCK = "category-product-count";
    public static final String TOMBSTONE_PURGE_LOCK = "tombstone-purge";

    public boolean expired(Instant now) {
        return leaseExpiresAt == null || leaseExpiresAt.isBefore(now);
    }
}
