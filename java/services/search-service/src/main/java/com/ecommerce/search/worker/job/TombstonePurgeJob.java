package com.ecommerce.search.worker.job;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.json.JsonData;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.LeaseLockDocument;
import com.ecommerce.search.repository.JobStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code WF-SEARCH-012} — physically deletes tombstoned documents once no late event can
 * bring them back.
 *
 * <p>The protection window must be longer than the retention of every topic Search
 * consumes, so that by the time a document disappears no producer can still replay an
 * event about it (ADR-011). Deletion is filtered on current state, so a run interrupted
 * halfway simply continues next time.
 */
@Component
public class TombstonePurgeJob {

    private static final Logger log = LoggerFactory.getLogger(TombstonePurgeJob.class);
    private static final String JOB = "tombstone-purge";

    private final ElasticsearchClient client;
    private final SearchProperties properties;
    private final JobStateRepository locks;
    private final SearchMetrics metrics;

    public TombstonePurgeJob(ElasticsearchClient client,
                             SearchProperties properties,
                             JobStateRepository locks,
                             SearchMetrics metrics) {
        this.client = client;
        this.properties = properties;
        this.locks = locks;
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
            long deleted = purge(properties.aliases().productsWrite(), cutoff)
                    + purge(properties.aliases().categoriesWrite(), cutoff);
            log.info("{} purged {} documents tombstoned before {}", JOB, deleted, cutoff);
            return deleted;
        } finally {
            locks.releaseLease(lease.get());
            metrics.jobDuration(JOB, Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    private long purge(String index, String cutoff) throws Exception {
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
