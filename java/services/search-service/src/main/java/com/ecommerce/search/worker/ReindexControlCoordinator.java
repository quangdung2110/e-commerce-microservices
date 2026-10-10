package com.ecommerce.search.worker;

import com.ecommerce.search.domain.document.ReindexRequestDocument;
import com.ecommerce.search.domain.event.CatalogPayloads;
import com.ecommerce.search.domain.event.CloudEventEnvelope;
import com.ecommerce.search.repository.JobStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Coalesces {@code catalog-reindex-requested} control events into one durable request
 * (ADR-017, ADR-022).
 *
 * <p>Product emits one of these when a category is renamed, re-slugged or moved. Search does
 * not fan the change out across SKU documents; it runs a full reindex, category export first.
 * Requests are coalesced by root category (the highest revision wins) so a burst of admin edits
 * produces one run, and they are stored in {@code search_jobs} before the Kafka offset is
 * committed, so neither a restart nor a failed run can lose a tree change. {@link
 * ReindexAutoStarter} turns the stored request into a run once it has been quiet for the
 * debounce interval, and a request that arrives after a run started makes that run
 * {@code SUPERSEDED} instead of letting it cut over an index with a stale category path.
 */
@Component
public class ReindexControlCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ReindexControlCoordinator.class);

    private final ObjectMapper objectMapper;
    private final JobStateRepository runs;

    public ReindexControlCoordinator(ObjectMapper esObjectMapper, JobStateRepository runs) {
        this.objectMapper = esObjectMapper;
        this.runs = runs;
    }

    /**
     * @return true when the event is stored or unusable (unusable events are logged and
     *         dropped); false when it could not be stored and the batch must be retried
     */
    public boolean onReindexRequested(CloudEventEnvelope event) {
        CatalogPayloads.CatalogReindexRequested payload;
        try {
            payload = objectMapper.treeToValue(event.data(), CatalogPayloads.CatalogReindexRequested.class);
        } catch (Exception e) {
            log.error("unusable catalog reindex control event {}: {}", event.id(), e.getMessage());
            return true;
        }
        if (payload.rootCategoryId() == null || payload.categoryRevision() == null) {
            log.error("control event {} is missing root_category_id or category_revision", event.id());
            return true;
        }
        try {
            runs.mergeReindexRequest(payload.rootCategoryId(), payload.categoryRevision(), Instant.now());
        } catch (IOException e) {
            log.warn("could not store the reindex request for root category {}, the batch will be retried: {}",
                    payload.rootCategoryId(), e.getMessage());
            return false;
        }
        log.info("coalesced reindex request for root category {} at revision {}",
                payload.rootCategoryId(), payload.categoryRevision());
        return true;
    }

    /** The stored request, if one is waiting for a run. */
    public Optional<ReindexRequestDocument> pending() throws IOException {
        return runs.findReindexRequest();
    }

    /** Remembers that a run was started for the stored request. */
    public void markAttempt(Instant at) throws IOException {
        runs.markReindexRequestAttempt(at);
    }

    /**
     * True when a request arrived after {@code since}. A run that began before it cannot be
     * trusted to have exported the changed tree, so it must not cut over.
     */
    public boolean supersedePending(Instant since) {
        try {
            return runs.findReindexRequest()
                    .map(ReindexRequestDocument::updatedAt)
                    .filter(updatedAt -> updatedAt != null && updatedAt.isAfter(since))
                    .isPresent();
        } catch (IOException e) {
            // Cannot tell: the safe answer for a run about to swap aliases is "not superseded"
            // only if the next check can still catch it, so report the doubt as a failure.
            throw new IllegalStateException("cannot read the pending reindex request", e);
        }
    }

    /** A completed run satisfied every request that arrived before it started. */
    public void satisfiedBy(Instant runStartedAt) {
        try {
            runs.deleteReindexRequestNotNewerThan(runStartedAt);
        } catch (IOException e) {
            log.warn("could not clear the satisfied reindex request; it will start one more run: {}", e.getMessage());
        }
    }
}
