package com.ecommerce.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.ProductDocument;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Reads of the {@code products} projection, always through the read alias. */
@Repository
public class ProductSearchRepository {

    private final ElasticsearchClient client;
    private final SearchProperties properties;
    private final SearchMetrics metrics;

    public ProductSearchRepository(ElasticsearchClient client, SearchProperties properties, SearchMetrics metrics) {
        this.client = client;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * One page of SKU hits plus the facet aggregations.
     *
     * <p>{@code track_total_hits} is on so {@code total_skus} is exact; that is a
     * deliberate cost to watch as the catalog grows ({@code docs/search/data.md §7}).
     */
    public SearchResponse<ProductDocument> search(Query query,
                                                  List<SortOptions> sort,
                                                  Map<String, Aggregation> aggregations,
                                                  int size,
                                                  List<FieldValue> searchAfter) throws IOException {
        long startedAt = System.nanoTime();
        SearchResponse<ProductDocument> response = client.search(builder -> {
            builder.index(properties.aliases().products())
                    .query(query)
                    .size(size)
                    .sort(sort)
                    .aggregations(aggregations)
                    .trackTotalHits(track -> track.enabled(true))
                    .timeout(properties.query().productTimeout().toMillis() + "ms");
            if (searchAfter != null && !searchAfter.isEmpty()) {
                builder.searchAfter(searchAfter);
            }
            return builder;
        }, ProductDocument.class);
        metrics.esQuery(properties.aliases().products(), "search",
                Duration.ofNanos(System.nanoTime() - startedAt));
        return response;
    }

    /** Aggregation-only request used by the category facet endpoint and the count job. */
    public SearchResponse<Void> aggregate(Query query, Map<String, Aggregation> aggregations,
                                          String queryType) throws IOException {
        long startedAt = System.nanoTime();
        SearchResponse<Void> response = client.search(builder -> builder
                .index(properties.aliases().products())
                .query(query)
                .size(0)
                .aggregations(aggregations)
                .trackTotalHits(track -> track.enabled(false))
                .timeout(properties.query().productTimeout().toMillis() + "ms"),
                Void.class);
        metrics.esQuery(properties.aliases().products(), queryType,
                Duration.ofNanos(System.nanoTime() - startedAt));
        return response;
    }
}
