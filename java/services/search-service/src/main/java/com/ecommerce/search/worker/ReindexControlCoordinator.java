package com.ecommerce.search.worker;

import com.ecommerce.search.domain.event.CatalogPayloads;
import com.ecommerce.search.domain.event.CloudEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coalesces {@code catalog-reindex-requested} control events (ADR-017).
 *
 * <p>Product emits one of these when a category is renamed, re-slugged or moved. Search
 * does not fan the change out across SKU documents; it runs a full reindex, category
 * export first. Requests are coalesced by root category so a burst of admin edits produces
 * one run, and a control event arriving during Build or Catch-up supersedes the run in
 * flight rather than letting it cut over an index with a stale category path.
 */
@Component
public class ReindexControlCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ReindexControlCoordinator.class);

    private final ObjectMapper objectMapper;
    /** Highest requested revision per root category; the coalescing key. */
    private final Map<String, Long> pendingByRootCategory = new ConcurrentHashMap<>();
    private final AtomicBoolean supersedeRequested = new AtomicBoolean(false);

    public ReindexControlCoordinator(ObjectMapper esObjectMapper) {
        this.objectMapper = esObjectMapper;
    }

    public void onReindexRequested(CloudEventEnvelope event) {
        CatalogPayloads.CatalogReindexRequested payload;
        try {
            payload = objectMapper.treeToValue(event.data(), CatalogPayloads.CatalogReindexRequested.class);
        } catch (Exception e) {
            log.error("unusable catalog reindex control event {}: {}", event.id(), e.getMessage());
            return;
        }
        if (payload.rootCategoryId() == null || payload.categoryRevision() == null) {
            log.error("control event {} is missing root_category_id or category_revision", event.id());
            return;
        }
        pendingByRootCategory.merge(payload.rootCategoryId(), payload.categoryRevision(), Math::max);
        supersedeRequested.set(true);
        log.info("coalesced reindex request for root category {} at revision {}",
                payload.rootCategoryId(), payload.categoryRevision());
    }

    /** True when a control event has arrived since the running reindex started. */
    public boolean supersedePending() {
        return supersedeRequested.get();
    }

    /**
     * Consumes the coalesced state at the moment a run starts, so a request arriving after
     * this point supersedes that run instead of being lost.
     */
    public Optional<String> consumePending() {
        supersedeRequested.set(false);
        if (pendingByRootCategory.isEmpty()) {
            return Optional.empty();
        }
        String summary = pendingByRootCategory.toString();
        pendingByRootCategory.clear();
        return Optional.of(summary);
    }
}
