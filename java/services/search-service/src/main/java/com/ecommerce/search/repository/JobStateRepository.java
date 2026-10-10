package com.ecommerce.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.IndexRetiredDocument;
import com.ecommerce.search.domain.document.LeaseLockDocument;
import com.ecommerce.search.domain.document.ReindexCancelDocument;
import com.ecommerce.search.domain.document.ReindexRequestDocument;
import com.ecommerce.search.domain.document.ReindexRunDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * State of the singleton jobs, held in the internal {@code search_jobs} index.
 *
 * <p>Search has no second store ({@code ADR-001}), so reindex runs, suggestion builds and
 * the distributed lease locks all live in Elasticsearch. This index is internal: it never
 * serves buyers and is not part of a reindex cutover.
 */
@Repository
public class JobStateRepository {

    private static final Logger log = LoggerFactory.getLogger(JobStateRepository.class);

    private final ElasticsearchClient client;
    private final SearchProperties properties;

    public JobStateRepository(ElasticsearchClient client, SearchProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    private String index() {
        return properties.aliases().searchJobs();
    }

    // ---------------------------------------------------------------- reindex runs

    public void saveRun(ReindexRunDocument run) throws IOException {
        client.index(builder -> builder
                .index(index())
                .id("run:" + run.runId())
                .document(run)
                .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
    }

    public Optional<ReindexRunDocument> findRun(String runId) throws IOException {
        GetResponse<ReindexRunDocument> response = client.get(
                builder -> builder.index(index()).id("run:" + runId), ReindexRunDocument.class);
        return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
    }

    /**
     * The run currently holding the singleton slot, if any. Only one run may be in a
     * non-terminal state at a time; a second trigger is rejected with
     * {@code REINDEX_ALREADY_RUNNING}.
     */
    public Optional<ReindexRunDocument> findActiveRun() throws IOException {
        SearchResponse<ReindexRunDocument> response = client.search(builder -> builder
                .index(index())
                .size(1)
                .trackTotalHits(track -> track.enabled(false))
                .query(q -> q.bool(bool -> bool
                        .filter(f -> f.term(t -> t.field("doc_kind").value(ReindexRunDocument.DOC_KIND)))
                        .mustNot(mustNot -> mustNot.terms(terms -> terms
                                .field("status")
                                .terms(values -> values.value(ReindexRunDocument.TERMINAL_STATUSES.stream()
                                        .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                        .toList()))))))
                .sort(sort -> sort.field(f -> f.field("started_at").order(SortOrder.Desc))),
                ReindexRunDocument.class);
        return response.hits().hits().stream().findFirst().map(hit -> hit.source());
    }

    /** Newest first, optionally narrowed to one status. */
    public List<ReindexRunDocument> listRuns(Optional<String> status, int limit) throws IOException {
        SearchResponse<ReindexRunDocument> response = client.search(builder -> builder
                .index(index())
                .size(limit)
                .trackTotalHits(track -> track.enabled(false))
                .query(q -> q.bool(bool -> {
                    bool.filter(f -> f.term(t -> t.field("doc_kind").value(ReindexRunDocument.DOC_KIND)));
                    status.ifPresent(value -> bool.filter(f -> f.term(t -> t.field("status").value(value))));
                    return bool;
                }))
                .sort(sort -> sort.field(f -> f.field("started_at").order(SortOrder.Desc))),
                ReindexRunDocument.class);
        return response.hits().hits().stream()
                .map(hit -> hit.source())
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * A cancellation is a document of its own, not a flag inside the run: the running job
     * replaces the whole run document at every checkpoint and would otherwise erase a request
     * that arrived between two of its saves.
     */
    public void requestCancel(String runId, String actor, Instant at) throws IOException {
        client.index(builder -> builder
                .index(index())
                .id(ReindexCancelDocument.docId(runId))
                .document(new ReindexCancelDocument(ReindexCancelDocument.DOC_KIND, runId, actor, at))
                .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
    }

    public boolean isCancelRequested(String runId) throws IOException {
        return client.exists(builder -> builder.index(index()).id(ReindexCancelDocument.docId(runId))).value();
    }

    public void clearCancel(String runId) throws IOException {
        client.delete(builder -> builder
                .index(index()).id(ReindexCancelDocument.docId(runId))
                .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
    }

    // ---------------------------------------------------------------- coalesced reindex request

    public Optional<ReindexRequestDocument> findReindexRequest() throws IOException {
        GetResponse<ReindexRequestDocument> response = client.get(
                builder -> builder.index(index()).id(ReindexRequestDocument.DOC_ID), ReindexRequestDocument.class);
        return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
    }

    /**
     * Records one {@code catalog-reindex-requested}: creates the pending request or raises the
     * revision of its root category, and moves {@code updated_at} to {@code now}. Concurrent
     * listener threads race on this document, so it is written with a sequence-number check and
     * retried; the revision only ever grows, which makes a repeated event harmless.
     */
    @SuppressWarnings("unchecked")
    public void mergeReindexRequest(String rootCategoryId, long revision, Instant now) throws IOException {
        for (int attempt = 0; attempt < 8; attempt++) {
            GetResponse<ReindexRequestDocument> current = client.get(
                    builder -> builder.index(index()).id(ReindexRequestDocument.DOC_ID),
                    ReindexRequestDocument.class);

            Map<String, Object> checkpoint = new java.util.LinkedHashMap<>();
            Map<String, Object> roots = new java.util.LinkedHashMap<>();
            Instant firstAt = now;
            if (current.found() && current.source() != null) {
                ReindexRequestDocument existing = current.source();
                if (existing.checkpoint() != null) {
                    checkpoint.putAll(existing.checkpoint());
                    Object known = existing.checkpoint().get("roots");
                    if (known instanceof Map<?, ?> map) {
                        roots.putAll((Map<String, Object>) map);
                    }
                }
                if (existing.startedAt() != null) {
                    firstAt = existing.startedAt();
                }
            }
            roots.merge(rootCategoryId, revision,
                    (old, added) -> Math.max(((Number) old).longValue(), ((Number) added).longValue()));
            checkpoint.put("roots", roots);
            ReindexRequestDocument merged = new ReindexRequestDocument(
                    ReindexRequestDocument.DOC_KIND, "CATEGORY_TREE_CHANGED", firstAt, now, checkpoint);
            try {
                if (current.found()) {
                    client.index(builder -> builder
                            .index(index()).id(ReindexRequestDocument.DOC_ID).document(merged)
                            .ifSeqNo(current.seqNo()).ifPrimaryTerm(current.primaryTerm())
                            .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
                } else {
                    client.create(builder -> builder
                            .index(index()).id(ReindexRequestDocument.DOC_ID).document(merged)
                            .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
                }
                return;
            } catch (ElasticsearchException e) {
                if (e.status() != 409) {
                    throw e;
                }
                log.debug("reindex request was updated concurrently, merging again (attempt {})", attempt + 1);
            }
        }
        throw new IOException("could not persist the reindex request after repeated version conflicts");
    }

    /** Remembers when an automatic run was last started for the pending request. */
    public void markReindexRequestAttempt(Instant at) throws IOException {
        try {
            client.update(builder -> builder
                            .index(index())
                            .id(ReindexRequestDocument.DOC_ID)
                            .retryOnConflict(5)
                            .refresh(co.elastic.clients.elasticsearch._types.Refresh.True)
                            .doc(Map.of("checkpoint", Map.of("last_attempt_at", at))),
                    ReindexRequestDocument.class);
        } catch (ElasticsearchException e) {
            if (e.status() != 404) {
                throw e;
            }
        }
    }

    /**
     * Drops the pending request once a completed run satisfied it, unless a newer event arrived
     * after the cutoff: a request that arrived after the run started asks for a tree the run
     * may not have seen.
     */
    public void deleteReindexRequestNotNewerThan(Instant cutoff) throws IOException {
        GetResponse<ReindexRequestDocument> current = client.get(
                builder -> builder.index(index()).id(ReindexRequestDocument.DOC_ID), ReindexRequestDocument.class);
        if (!current.found() || current.source() == null) {
            return;
        }
        Instant updatedAt = current.source().updatedAt();
        if (updatedAt != null && updatedAt.isAfter(cutoff)) {
            return;
        }
        try {
            client.delete(builder -> builder
                    .index(index()).id(ReindexRequestDocument.DOC_ID)
                    .ifSeqNo(current.seqNo()).ifPrimaryTerm(current.primaryTerm())
                    .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
        } catch (ElasticsearchException e) {
            if (e.status() != 409 && e.status() != 404) {
                throw e;
            }
        }
    }

    // ---------------------------------------------------------------- retired indices

    public void markIndexRetired(String index, String runId, Instant at) throws IOException {
        client.index(builder -> builder
                .index(index())
                .id(IndexRetiredDocument.docId(index))
                .document(new IndexRetiredDocument(IndexRetiredDocument.DOC_KIND, index, runId, at))
                .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
    }

    /** Called when an index becomes current again (rollback) or is gone. */
    public void clearIndexRetired(String index) throws IOException {
        client.delete(builder -> builder
                .index(index())
                .id(IndexRetiredDocument.docId(index))
                .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
    }

    /** Retirement instant per physical index name. */
    public Map<String, Instant> retiredIndices() throws IOException {
        SearchResponse<IndexRetiredDocument> response = client.search(builder -> builder
                .index(index())
                .size(1000)
                .trackTotalHits(track -> track.enabled(false))
                .query(q -> q.term(t -> t.field("doc_kind").value(IndexRetiredDocument.DOC_KIND))),
                IndexRetiredDocument.class);
        Map<String, Instant> retired = new java.util.HashMap<>();
        response.hits().hits().forEach(hit -> {
            IndexRetiredDocument doc = hit.source();
            if (doc != null && doc.targetIndex() != null && doc.finishedAt() != null) {
                retired.put(doc.targetIndex(), doc.finishedAt());
            }
        });
        return retired;
    }

    // ---------------------------------------------------------------- lease locks

    /**
     * Acquires a lease with an atomic create, or steals one whose lease has expired using
     * an optimistic {@code if_seq_no}/{@code if_primary_term} update. The fencing token is
     * monotonic, so a resurrected old owner can be told apart from the current one.
     *
     * @return the acquired lease, or empty when another owner still holds a live lease
     */
    public Optional<LeaseLockDocument> acquireLease(String lockName, String runId, Instant now, Instant expiresAt)
            throws IOException {
        String docId = "lock:" + lockName;
        GetResponse<LeaseLockDocument> current = client.get(
                builder -> builder.index(index()).id(docId), LeaseLockDocument.class);

        if (!current.found()) {
            LeaseLockDocument lease = new LeaseLockDocument(
                    LeaseLockDocument.DOC_KIND, lockName, runId, expiresAt, 1L, now);
            try {
                client.create(builder -> builder
                        .index(index())
                        .id(docId)
                        .document(lease)
                        .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
                return Optional.of(lease);
            } catch (ElasticsearchException e) {
                log.debug("lease {} was created concurrently by another replica", lockName);
                return Optional.empty();
            }
        }

        LeaseLockDocument existing = current.source();
        if (existing != null && !existing.expired(now)) {
            return Optional.empty();
        }

        long nextToken = (existing == null || existing.fencingToken() == null ? 0L : existing.fencingToken()) + 1;
        LeaseLockDocument lease = new LeaseLockDocument(
                LeaseLockDocument.DOC_KIND, lockName, runId, expiresAt, nextToken, now);
        try {
            client.index(builder -> builder
                    .index(index())
                    .id(docId)
                    .document(lease)
                    .ifSeqNo(current.seqNo())
                    .ifPrimaryTerm(current.primaryTerm())
                    .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
            log.info("took over expired lease {} with fencing token {}", lockName, nextToken);
            return Optional.of(lease);
        } catch (ElasticsearchException e) {
            log.debug("lease {} was taken over concurrently", lockName);
            return Optional.empty();
        }
    }

    /**
     * Extends a lease the caller still owns. Returns false when the lease has moved on —
     * the owner must then abort, because a later run may already be cutting over.
     */
    public boolean renewLease(LeaseLockDocument lease, Instant now, Instant expiresAt) throws IOException {
        Optional<LeaseLockDocument> current = readLease(lease.lockName());
        if (current.isEmpty()
                || !lease.ownerRunId().equals(current.get().ownerRunId())
                || !lease.fencingToken().equals(current.get().fencingToken())) {
            return false;
        }
        LeaseLockDocument renewed = new LeaseLockDocument(
                LeaseLockDocument.DOC_KIND, lease.lockName(), lease.ownerRunId(),
                expiresAt, lease.fencingToken(), now);
        client.index(builder -> builder.index(index()).id("lock:" + lease.lockName()).document(renewed));
        return true;
    }

    /** True when the caller still owns the lease; every alias swap must check this. */
    public boolean holdsLease(LeaseLockDocument lease) throws IOException {
        return readLease(lease.lockName())
                .filter(current -> lease.ownerRunId().equals(current.ownerRunId()))
                .filter(current -> lease.fencingToken().equals(current.fencingToken()))
                .isPresent();
    }

    /** Releases a lease, but only if the caller is still its owner. */
    public void releaseLease(LeaseLockDocument lease) throws IOException {
        if (!holdsLease(lease)) {
            return;
        }
        client.delete(builder -> builder
                .index(index())
                .id("lock:" + lease.lockName())
                .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
    }

    public Optional<LeaseLockDocument> readLease(String lockName) throws IOException {
        GetResponse<LeaseLockDocument> response = client.get(
                builder -> builder.index(index()).id("lock:" + lockName), LeaseLockDocument.class);
        return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
    }

    // ---------------------------------------------------------------- suggestion builds

    /** Persists suggestion-build metadata: phase, source indices, versions, validation. */
    public void saveSuggestionBuild(String runId, Map<String, Object> state) throws IOException {
        client.index(builder -> builder
                .index(index())
                .id("suggestion-build:" + runId)
                .document(state)
                .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
    }

    /** Most recent suggestion build, used to find the rollback version. */
    public Optional<Map<String, Object>> findLatestSuggestionBuild() throws IOException {
        SearchResponse<Map> response = client.search(builder -> builder
                .index(index())
                .size(1)
                .trackTotalHits(track -> track.enabled(false))
                .query(Query.of(q -> q.term(t -> t.field("doc_kind").value("SUGGESTION_BUILD"))))
                .sort(sort -> sort.field(f -> f.field("started_at").order(SortOrder.Desc))),
                Map.class);
        List<?> hits = response.hits().hits();
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>)
                ((co.elastic.clients.elasticsearch.core.search.Hit<?>) hits.get(0)).source();
        return Optional.ofNullable(source);
    }
}
