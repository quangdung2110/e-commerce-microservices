package com.ecommerce.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.service.ProductQueryBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An Elasticsearch search that hits its {@code timeout} answers {@code 200} with
 * {@code timed_out: true} and whatever it had collected so far. WF-SEARCH-004 and
 * WF-SEARCH-011 both promise that such a result is never used: the facet endpoint answers
 * {@code SEARCH_TIMEOUT} and the count job keeps the previous numbers.
 */
class ProductSearchRepositoryAggregateTest {

    @Test
    @SuppressWarnings("unchecked")
    void aTimedOutAggregationIsNotReturnedAsIfItWereComplete() throws IOException {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        SearchResponse<Void> partial = SearchResponse.<Void>of(b -> b
                .took(5000)
                .timedOut(true)
                .shards(s -> s.total(3).successful(3).failed(0))
                .hits(h -> h.hits(List.of())));
        when(client.search(any(Function.class), eq(Void.class))).thenReturn(partial);

        SearchProperties properties = mock(SearchProperties.class);
        when(properties.aliases()).thenReturn(new SearchProperties.Aliases(
                "products", "products_write", "categories", "categories_write",
                "search_suggestions", "search_jobs"));
        when(properties.query()).thenReturn(new SearchProperties.QueryBudget(
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofMinutes(2),
                1000, 50, 100));

        ProductSearchRepository repository = new ProductSearchRepository(client, properties,
                new SearchMetrics(new SimpleMeterRegistry()), mock(ProductQueryBuilder.class), "2m", 500, 50_000);

        assertThatThrownBy(() -> repository.aggregate(Query.of(q -> q.matchAll(m -> m)), Map.of(), "category_facets"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timeout");
    }
}
