package com.ecommerce.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.model.ProductMatch;
import com.ecommerce.search.service.ProductQueryBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** SKU projection read through its alias; product grouping is bound to one PIT. */
@Repository
public class ProductSearchRepository {
    private final ElasticsearchClient client;
    private final SearchProperties properties;
    private final SearchMetrics metrics;
    private final ProductQueryBuilder queryBuilder;
    private final String keepAlive;
    private final int batchSize;
    private final int maxProducts;

    public ProductSearchRepository(ElasticsearchClient client, SearchProperties properties, SearchMetrics metrics,
            ProductQueryBuilder queryBuilder,
            @Value("${search.pagination.pit-keep-alive:2m}") String keepAlive,
            @Value("${search.pagination.product-scan-batch-size:500}") int batchSize,
            @Value("${search.pagination.max-matching-products:50000}") int maxProducts) {
        this.client = client; this.properties = properties; this.metrics = metrics;
        this.queryBuilder = queryBuilder; this.keepAlive = keepAlive; this.batchSize = batchSize; this.maxProducts = maxProducts;
        if (batchSize < 1 || maxProducts < 1) throw new IllegalArgumentException("product scan limits must be positive");
    }

    public String openSnapshot() throws IOException {
        return client.openPointInTime(b -> b.index(properties.aliases().products())
                .keepAlive(t -> t.time(keepAlive))).id();
    }

    /** Best effort close. Abandoned pagination expires automatically after keepAlive. */
    public void closeSnapshot(String pit) {
        try { client.closePointInTime(b -> b.id(pit)); } catch (Exception ignored) { /* TTL reclaims it */ }
    }

    public record ProductSnapshot(List<ProductMatch> products, Map<String, Aggregate> facets, String pitId) {}

    /** Enumerate ALL matching groups before sorting/paging, never approximate the total. */
    public ProductSnapshot products(Query query, String initialPit) throws IOException {
        long started = System.nanoTime();
        long deadline = started + properties.query().productTimeout().toNanos();
        String pit = initialPit;
        Map<String, FieldValue> after = null;
        List<ProductMatch> products = new ArrayList<>();
        Map<String, Aggregate> facets = Map.of();
        try {
            do {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("product grouping timeout: exceeded query budget");
                Map<String, Aggregation> aggregations = new HashMap<>();
                aggregations.put("products", queryBuilder.productGroups(batchSize, after));
                if (after == null) aggregations.putAll(queryBuilder.facetAggregations());
                String currentPit = pit;
                SearchResponse<Void> response = client.search(b -> b
                        .pit(p -> p.id(currentPit).keepAlive(t -> t.time(keepAlive)))
                        .query(query).size(0).trackTotalHits(t -> t.enabled(false))
                        .allowPartialSearchResults(false).timeout(Math.max(1, remaining / 1_000_000) + "ms")
                        .aggregations(aggregations), Void.class);
                if (response.pitId() != null) pit = response.pitId();
                if (response.timedOut()) throw new IOException("product grouping timeout: incomplete results");
                if (response.shards().failed().intValue() > 0) throw new IOException("product grouping returned incomplete shard results");
                if (after == null) facets = response.aggregations();
                var groups = response.aggregations().get("products").composite();
                for (var bucket : groups.buckets().array()) {
                    var hits = bucket.aggregations().get("representative").topHits().hits().hits();
                    if (hits.isEmpty() || hits.get(0).source() == null) throw new IOException("product representative has no source");
                    ProductDocument doc = hits.get(0).source().to(ProductDocument.class);
                    if (doc.sellingPriceVnd() == null || doc.skuId() == null || doc.productId() == null) throw new IOException("product representative has incomplete catalog data");
                    products.add(new ProductMatch(doc, metric(bucket.aggregations(), "relevance"),
                            metric(bucket.aggregations(), "newest"), metric(bucket.aggregations(), "rating")));
                    if (products.size() > maxProducts) throw new IOException("matching products exceed configured grouping limit; narrow the search");
                }
                after = groups.afterKey();
                if (groups.buckets().array().isEmpty()) break;
            } while (after != null && !after.isEmpty());
            if (System.nanoTime() > deadline) throw new IOException("product grouping timeout: exceeded query budget");
            return new ProductSnapshot(List.copyOf(products), facets, pit);
        } catch (ElasticsearchException e) {
            closeSnapshot(pit);
            if (e.status() == 404) throw SearchException.invalidCursor("search snapshot expired; restart pagination");
            throw e;
        } catch (IOException | RuntimeException e) {
            closeSnapshot(pit);
            throw e;
        } finally {
            metrics.esQuery(properties.aliases().products(), "product_groups", Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private static double metric(Map<String, Aggregate> aggs, String name) {
        Double value = aggs.get(name).max().value();
        return value == null || !Double.isFinite(value) ? 0 : value;
    }

    /** Facet and category-count jobs retain their aggregation-only SKU query. */
    public SearchResponse<Void> aggregate(Query query, Map<String, Aggregation> aggregations, String queryType) throws IOException {
        long started = System.nanoTime();
        SearchResponse<Void> response = client.search(b -> b.index(properties.aliases().products()).query(query)
                .size(0).aggregations(aggregations).trackTotalHits(t -> t.enabled(false))
                .timeout(properties.query().productTimeout().toMillis() + "ms"), Void.class);
        metrics.esQuery(properties.aliases().products(), queryType, Duration.ofNanos(System.nanoTime() - started));
        return response;
    }
}
