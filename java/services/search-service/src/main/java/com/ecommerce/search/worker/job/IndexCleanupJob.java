package com.ecommerce.search.worker.job;

import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.LeaseLockDocument;
import com.ecommerce.search.domain.document.ReindexRunDocument;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.repository.es.IndexAdmin;
import com.ecommerce.search.repository.es.IndexTemplates;
import com.ecommerce.search.repository.es.ProductCardIndex;
import com.ecommerce.search.worker.productcards.ProductCardProjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Deletes physical indices that nothing serves any more, once they have been kept long enough
 * to roll back to ({@code data.md §11}, ADR-022).
 *
 * <p>The age of a superseded index is measured from the moment it stopped being current, not
 * from its creation (a reindex builds for hours before it cuts over): the cutover and the
 * suggestion swap write an {@code INDEX_RETIRED} marker for the index they replace. An index
 * without a marker that is not current and not owned by an unfinished run is given one now, so
 * an index left behind by an older release or by hand is eventually removed instead of living
 * forever.
 *
 * <ul>
 *   <li>{@code products} and {@code categories}: every non-current version is deleted once the
 *       retention has passed since it was retired.
 *   <li>{@code search_suggestions}: the newest retired version is the rollback version and is
 *       always kept; older retired versions go after the retention.
 * </ul>
 *
 * <p>An index of an unfinished run is never touched, nor is one that an alias serves.
 */
@Component
public class IndexCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IndexCleanupJob.class);
    private static final String JOB = "index-cleanup";
    private static final String LOCK = "index-cleanup";

    private final IndexAdmin indexAdmin;
    private final JobStateRepository runs;
    private final SearchProperties properties;
    private final SearchMetrics metrics;
    private Duration productPitKeepAlive = Duration.ofMinutes(2);

    @org.springframework.beans.factory.annotation.Value("${search.pagination.pit-keep-alive:2m}")
    void productPitKeepAlive(String value) {
        productPitKeepAlive = org.springframework.boot.convert.DurationStyle.detectAndParse(value);
    }

    public IndexCleanupJob(IndexAdmin indexAdmin, JobStateRepository runs, SearchProperties properties,
                           SearchMetrics metrics) {
        this.indexAdmin = indexAdmin;
        this.runs = runs;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${search.lifecycle.cleanup-cron:0 15 * * * *}")
    public void scheduled() {
        if (!properties.lifecycle().cleanupEnabled()) {
            return;
        }
        try {
            run();
        } catch (Exception e) {
            log.error("{} failed; the next run continues from the current state: {}", JOB, e.getMessage());
        }
    }

    /** @return number of indices deleted */
    public int run() throws IOException {
        Instant now = Instant.now();
        Optional<LeaseLockDocument> lease = runs.acquireLease(LOCK, JOB + ":" + now.toEpochMilli(),
                now, now.plus(properties.lifecycle().leaseDuration()));
        if (lease.isEmpty()) {
            log.info("{} skipped this cycle: another replica holds the lease", JOB);
            return 0;
        }
        try {
            Set<String> inUse = indicesOfUnfinishedRuns();
            Map<String, Instant> retired = runs.retiredIndices();
            int deleted = 0;
            deleted += clean(IndexTemplates.PRODUCTS, List.of(properties.aliases().products()),
                    false, inUse, retired, now);
            deleted += clean(IndexTemplates.CATEGORIES, List.of(properties.aliases().categories()),
                    false, inUse, retired, now);
            // Also covers a long private build before its completed target is checkpointed.
            if (runs.readLease(ProductCardProjector.LEASE).filter(held -> !held.expired(now)).isEmpty()) {
                deleted += clean(IndexTemplates.PRODUCT_CARDS, List.of(ProductCardIndex.ALIAS),
                        false, inUse, retired, now);
            }
            deleted += clean(IndexTemplates.SEARCH_SUGGESTIONS, List.of(properties.aliases().searchSuggestions()),
                    true, inUse, retired, now);
            forgetDeleted(retired);
            if (deleted > 0) {
                log.info("{} deleted {} superseded index(es)", JOB, deleted);
            }
            return deleted;
        } finally {
            runs.releaseLease(lease.get());
        }
    }

    private int clean(String template, List<String> aliases, boolean keepRollbackVersion,
                      Set<String> inUse, Map<String, Instant> retired, Instant now) throws IOException {
        Set<String> serving = new HashSet<>();
        for (String alias : aliases) {
            indexAdmin.resolveAlias(alias).ifPresent(serving::add);
        }
        List<String> candidates = indexAdmin.versionedIndices(template).stream()
                .filter(index -> !serving.contains(index) && !inUse.contains(index))
                .toList();

        // Indices seen for the first time start their clock now; they are never deleted in the
        // same pass, and never count as the rollback version.
        Set<String> alreadyKnown = new HashSet<>(retired.keySet());
        for (String index : candidates) {
            if (!retired.containsKey(index)) {
                runs.markIndexRetired(index, null, now);
                retired.put(index, now);
            }
        }

        Optional<String> rollbackVersion = keepRollbackVersion
                ? candidates.stream().filter(alreadyKnown::contains)
                        .max(Comparator.comparingInt(index -> IndexAdmin.versionOf(index).orElse(0)))
                : Optional.empty();

        int deleted = 0;
        for (String index : candidates) {
            if (rollbackVersion.filter(index::equals).isPresent()) {
                continue;
            }
            Instant retiredAt = retired.get(index);
            Duration retention = properties.lifecycle().oldIndexRetention();
            if (IndexTemplates.PRODUCT_CARDS.equals(template) && retention.compareTo(productPitKeepAlive) < 0)
                retention = productPitKeepAlive;
            if (!alreadyKnown.contains(index) || retiredAt.plus(retention).isAfter(now)) {
                continue;
            }
            if (IndexTemplates.PRODUCT_CARDS.equals(template) && indexAdmin.hasOpenSearchContexts(index)) continue;
            indexAdmin.deleteIndex(index);
            runs.clearIndexRetired(index);
            metrics.indexCleaned(template);
            deleted++;
        }
        return deleted;
    }

    /** The two indices each unfinished run is building; they have no marker and must be left alone. */
    private Set<String> indicesOfUnfinishedRuns() throws IOException {
        Set<String> indices = new HashSet<>();
        Optional<ReindexRunDocument> active = runs.findActiveRun();
        if (active.isPresent()) {
            ReindexProgressState state = ReindexProgressState.restore(active.get());
            if (state.productsIndex() != null) {
                indices.add(state.productsIndex());
            }
            if (state.categoriesIndex() != null) {
                indices.add(state.categoriesIndex());
            }
            if (state.productCardsIndex() != null) indices.add(state.productCardsIndex());
            if (active.get().targetIndex() != null) {
                indices.add(active.get().targetIndex());
            }
        }
        return indices;
    }

    /** Drops markers whose index is gone, so the marker set does not grow without bound. */
    private void forgetDeleted(Map<String, Instant> retired) throws IOException {
        for (String index : List.copyOf(retired.keySet())) {
            if (!indexAdmin.indexExists(index)) {
                runs.clearIndexRetired(index);
            }
        }
    }
}
