package com.ecommerce.search.worker.job;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.LeaseLockDocument;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.repository.es.ThrottledBulkWriter;
import com.ecommerce.search.worker.client.InventoryReadinessClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code WF-SEARCH-012} — physically deletes tombstoned documents once no late event can
 * bring them back.
 *
 * <p>Two preconditions, not one. The protection window must be longer than the retention of
 * every topic Search consumes, so no producer can still replay an event about the document
 * (ADR-011); and Inventory readiness for the SKU must be revoked first, so no availability
 * fact can be published for an identifier that is about to stop existing (ADR-019). A SKU
 * whose revocation does not succeed simply stays in the index — deletion is filtered on
 * current state, so the next run picks it up again.
 *
 * <p>Categories carry no readiness: nothing outside Search projects into them, so they are
 * still purged with a single {@code _delete_by_query}.
 */
@Component
public class TombstonePurgeJob {

    private static final Logger log = LoggerFactory.getLogger(TombstonePurgeJob.class);
    private static final String JOB = "tombstone-purge";

    private final ElasticsearchClient client;
    private final SearchProperties properties;
    private final JobStateRepository locks;
    private final InventoryReadinessClient readiness;
    private final ThrottledBulkWriter bulkWriter;
    private final SearchMetrics metrics;

    public TombstonePurgeJob(ElasticsearchClient client,
                             SearchProperties properties,
                             JobStateRepository locks,
                             InventoryReadinessClient readiness,
                             ThrottledBulkWriter bulkWriter,
                             SearchMetrics metrics) {
        this.client = client;
        this.properties = properties;
        this.locks = locks;
        this.readiness = readiness;
        this.bulkWriter = bulkWriter;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${search.tombstone.purge-cron:0 30 2 * * *}")
    public void scheduled() {
        if (!properties.tombstone().purgeEnabled()) {
            return;
        }
        try {
            run();
        } catch (Exception e) {
            log.error("{} failed; the next run continues from the current state: {}", JOB, e.getMessage());
        }
    }

    /** @return number of documents physically deleted */
    public long run() throws Exception {
        Instant now = Instant.now();
        Optional<LeaseLockDocument> lease = locks.acquireLease(
                LeaseLockDocument.TOMBSTONE_PURGE_LOCK, JOB + ":" + now.toEpochMilli(),
                now, now.plus(properties.suggestions().leaseDuration()));
        if (lease.isEmpty()) {
            log.info("{} skipped this cycle: another replica holds the lease", JOB);
            return 0;
        }

        long startedAt = System.nanoTime();
        try {
            String cutoff = Timestamps.iso(now.minus(properties.tombstone().protectionWindow()));
            long deleted = purgeSkus(cutoff, now)
                    + purgeCategories(properties.aliases().categoriesWrite(), cutoff);
            log.info("{} purged {} documents tombstoned before {}", JOB, deleted, cutoff);
            return deleted;
        } finally {
            locks.releaseLease(lease.get());
            metrics.jobDuration(JOB, Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    /**
     * Revokes readiness for each due SKU, then deletes the ones Inventory agreed to release.
     *
     * <p>Paging is by {@code sku_id} with {@code search_after} and the query re-runs from the
     * start of each page's key, so documents deleted in this run drop out naturally and a
     * SKU that was skipped is retried on the next cycle rather than blocking the scan.
     */
    private long purgeSkus(String cutoff, Instant now) throws IOException {
        String index = properties.aliases().productsWrite();
        int pageSize = properties.reindex().exportPageSize();
        Instant deadline = now.plus(properties.suggestions().leaseDuration());

        long deleted = 0;
        long blocked = 0;
        String after = null;
        try (ThrottledBulkWriter.Session session = bulkWriter.open(JOB, deadline)) {
            while (true) {
                List<Hit<JsonNode>> hits = duePage(index, cutoff, after, pageSize);
                if (hits.isEmpty()) {
                    break;
                }
                for (Hit<JsonNode> hit : hits) {
                    after = hit.id();
                    Long catalogRevision = catalogRevisionOf(hit);
                    if (catalogRevision == null) {
                        log.error("tombstoned sku {} carries no catalog_revision, so readiness cannot be "
                                + "revoked for it; leaving it in place", hit.id());
                        blocked++;
                        continue;
                    }
                    if (readiness.revoke(hit.id(), catalogRevision) != InventoryReadinessClient.Outcome.OK) {
                        // FAILED retries next cycle; CONFLICT is a divergence the client has
                        // already logged and counted. Neither may lead to a delete.
                        blocked++;
                        continue;
                    }
                    session.add(BulkOperation.of(op -> op.delete(del -> del.index(index).id(hit.id()))), 64);
                    deleted++;
                }
                if (hits.size() < pageSize) {
                    break;
                }
            }
            session.flush();
            if (!session.failures().isEmpty()) {
                log.error("{} could not delete {} document(s): {}", JOB, session.failures().size(),
                        session.failures());
            }
        }
        if (blocked > 0) {
            log.warn("{} left {} tombstoned sku(s) in place because readiness was not revoked", JOB, blocked);
        }
        return deleted;
    }

    /** One page of tombstones past their protection window, ordered by {@code sku_id}. */
    private List<Hit<JsonNode>> duePage(String index, String cutoff, String after, int pageSize)
            throws IOException {
        SearchResponse<JsonNode> response = client.search(builder -> {
            builder.index(index)
                    .size(pageSize)
                    .trackTotalHits(track -> track.enabled(false))
                    .sort(sort -> sort.field(field -> field.field("sku_id").order(SortOrder.Asc)))
                    .source(source -> source.filter(filter -> filter.includes("catalog_revision")))
                    .query(query -> query.bool(bool -> bool
                            .filter(filter -> filter.term(term -> term.field("is_deleted").value(true)))
                            .filter(filter -> filter.range(range -> range
                                    .field("deleted_at")
                                    .lt(JsonData.of(cutoff))))));
            if (after != null) {
                builder.searchAfter(co.elastic.clients.elasticsearch._types.FieldValue.of(after));
            }
            return builder;
        }, JsonNode.class);
        return response.hits().hits();
    }

    private static Long catalogRevisionOf(Hit<JsonNode> hit) {
        JsonNode source = hit.source();
        if (source == null || !source.hasNonNull("catalog_revision")) {
            return null;
        }
        return source.get("catalog_revision").asLong();
    }

    private long purgeCategories(String index, String cutoff) throws IOException {
        DeleteByQueryResponse response = client.deleteByQuery(builder -> builder
                .index(index)
                .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
                .requestsPerSecond(properties.bulk().taskRequestsPerSecond())
                .query(query -> query.bool(bool -> bool
                        .filter(filter -> filter.term(term -> term.field("is_deleted").value(true)))
                        .filter(filter -> filter.range(range -> range
                                .field("deleted_at")
                                .lt(JsonData.of(cutoff)))))));
        return response.deleted() == null ? 0L : response.deleted();
    }
}
