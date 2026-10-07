package com.ecommerce.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.LeaseLockDocument;
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
