package com.ecommerce.search.worker.job;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.document.LeaseLockDocument;
import com.ecommerce.search.repository.CategoryRepository;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.repository.ProductSearchRepository;
import com.ecommerce.search.repository.es.IndexAdmin;
import com.ecommerce.search.repository.es.ProjectionWriter;
import com.ecommerce.search.repository.es.ThrottledBulkWriter;
import com.ecommerce.search.service.ProductQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code WF-SEARCH-011} — recomputes {@code categories.product_count}.
 *
 * <p>The job writes an absolute value, never an increment: a catalog event is a full
 * snapshot and says nothing about the previous state, so incrementing would be neither
 * derivable nor idempotent (ADR-008). Running it twice converges on the same numbers, and
 * a failed run leaves the previous counts alone — a stale count beats a wrong one.
 *
 * <p>Counting is done over {@code category_ancestor_ids} rather than {@code category_id},
 * so a parent shows the whole subtree instead of zero when products only sit on leaves.
 */
@Component
public class CategoryProductCountJob {

    private static final Logger log = LoggerFactory.getLogger(CategoryProductCountJob.class);
    private static final String JOB = "category-product-count";
    private static final String AGG = "by_category";

    private final SearchProperties properties;
    private final ProductSearchRepository products;
    private final CategoryRepository categories;
    private final ProductQueryBuilder queryBuilder;
    private final ProjectionWriter writer;
    private final ThrottledBulkWriter bulkWriter;
    private final JobStateRepository locks;
    private final IndexAdmin indexAdmin;
    private final SearchMetrics metrics;

    public CategoryProductCountJob(SearchProperties properties,
                                   ProductSearchRepository products,
                                   CategoryRepository categories,
                                   ProductQueryBuilder queryBuilder,
                                   ProjectionWriter writer,
                                   ThrottledBulkWriter bulkWriter,
                                   JobStateRepository locks,
                                   IndexAdmin indexAdmin,
                                   SearchMetrics metrics) {
        this.properties = properties;
        this.products = products;
        this.categories = categories;
        this.queryBuilder = queryBuilder;
        this.writer = writer;
        this.bulkWriter = bulkWriter;
        this.locks = locks;
        this.indexAdmin = indexAdmin;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${search.tombstone.product-count-cron:0 0 */4 * * *}")
    public void scheduled() {
        if (!properties.tombstone().productCountEnabled()) {
            return;
        }
        try {
            run();
        } catch (Exception e) {
            log.error("{} failed; the previous counts are kept: {}", JOB, e.getMessage());
        }
    }

    /** @return number of category documents updated */
    public long run() throws Exception {
        Instant now = Instant.now();
        Optional<LeaseLockDocument> lease = locks.acquireLease(
                LeaseLockDocument.PRODUCT_COUNT_LOCK, JOB + ":" + now.toEpochMilli(),
                now, now.plus(properties.suggestions().leaseDuration()));
        if (lease.isEmpty()) {
            log.info("{} skipped this cycle: another replica holds the lease", JOB);
            return 0;
        }

        long startedAt = System.nanoTime();
        try {
            return recompute();
        } finally {
            locks.releaseLease(lease.get());
            metrics.jobDuration(JOB, Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    private long recompute() throws Exception {
        Query visible = Query.of(q -> q.bool(bool -> {
            queryBuilder.visibilityFilters(true).forEach(bool::filter);
            return bool;
        }));

        Aggregation aggregation = Aggregation.of(agg -> agg
                .terms(terms -> terms.field("category_ancestor_ids").size(10_000))
                .aggregations(ProductQueryBuilder.DISTINCT_PRODUCTS_AGG, sub -> sub
                        .cardinality(card -> card
                                .field("product_id")
                                .precisionThreshold(properties.query().cardinalityPrecisionThreshold()))));

        var response = products.aggregate(visible, Map.of(AGG, aggregation), "product_count_recompute");
        Aggregate result = response.aggregations().get(AGG);
        if (result == null || !result.isSterms()) {
            throw new IllegalStateException("product_count aggregation returned no terms bucket");
        }

        Map<String, Long> countByCategory = new LinkedHashMap<>();
        for (StringTermsBucket bucket : result.sterms().buckets().array()) {
            Aggregate distinct = bucket.aggregations().get(ProductQueryBuilder.DISTINCT_PRODUCTS_AGG);
            countByCategory.put(bucket.key().stringValue(),
                    distinct != null && distinct.isCardinality() ? distinct.cardinality().value() : 0L);
        }

        String computedAt = Timestamps.nowIso();
        String index = properties.aliases().categoriesWrite();
        long updated = 0;

        try (ThrottledBulkWriter.Session session =
                     bulkWriter.open(JOB, Instant.now().plus(Duration.ofMinutes(30)))) {
            // Every visible category is written, including the ones that dropped out of the
            // aggregation entirely — they must go back to 0 rather than keep a stale count.
            for (CategoryDocument category : categories.findAllVisible()) {
                long count = countByCategory.getOrDefault(category.categoryId(), 0L);
                session.add(writer.categoryProductCount(index, category.categoryId(), count, computedAt), 200);
                updated++;
            }
        }
        indexAdmin.refresh(index);
        log.info("{} wrote absolute counts for {} categories", JOB, updated);
        return updated;
    }
}
