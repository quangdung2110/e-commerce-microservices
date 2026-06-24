package com.ecommerce.search.worker;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.json.JsonData;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.repository.ProductSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Daily cronjob that permanently removes ("purges") documents that were
 * soft-deleted ({@code is_deleted = true}) past the tombstone retention period.
 *
 * <p>This is the second stage of the two-phase cleanup process:
 * <ol>
 *   <li><b>Stage 1 — {@link DraftCleanupJob}:</b> Marks orphaned drafts as
 *       deleted (sets {@code is_deleted = true}). Runs at 02:00 AM daily.</li>
 *   <li><b>Stage 2 — TombstonePurger (this class):</b> Hard-deletes documents
 *       that have been in tombstone state for {@code tombstoneRetentionDays}.
 *       Runs at 02:30 AM daily.</li>
 * </ol>
 *
 * <p>The 30-minute gap between stages provides a safety window: if a document
 * was incorrectly marked as deleted by {@code DraftCleanupJob}, an admin has
 * until 02:30 AM to revert it.
 *
 * <p><b>Query conditions (both must be met):</b>
 * <ul>
 *   <li>{@code is_deleted = true}</li>
 *   <li>{@code deleted_at < now - tombstoneRetentionDays}</li>
 * </ul>
 *
 * <p>Schedule: configured via {@code search.draft-cleanup.tombstone-purger-cron}
 * (default 02:30 AM daily).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TombstonePurger {

    private final ProductSearchRepository productRepository;
    private final SearchProperties properties;

    @Scheduled(cron = "${search.draft-cleanup.tombstone-purger-cron}")
    public void purgeTombstones() {
        long startTime = System.currentTimeMillis();
        int retentionDays = properties.getDraftCleanup().getTombstoneRetentionDays();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        log.info("Starting Tombstone Purger: cutoff={}, retentionDays={}", cutoff, retentionDays);

        try {
            // Query: is_deleted = true AND deleted_at < cutoff
            Query query = Query.of(q -> q.bool(BoolQuery.of(b -> b
                .filter(
                    Query.of(qf -> qf.term(t -> t.field("is_deleted").value(true))),
                    Query.of(qf -> qf.range(RangeQuery.of(r -> r
                        .field("deleted_at")
                        .lt(JsonData.of(cutoff.toString()))
                    )))
                )
            )));

            DeleteByQueryResponse response = productRepository.deleteByQuery(query);
            log.info("Tombstone Purger: deleted={}, total matched={}",
                response.deleted(), response.total());

        } catch (Exception e) {
            log.error("Tombstone Purger failed", e);
            // Don't rethrow — non-critical best-effort job
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Tombstone Purger completed in {} ms", duration);
    }
}
