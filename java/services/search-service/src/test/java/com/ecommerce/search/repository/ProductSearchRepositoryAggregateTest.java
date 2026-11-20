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
 * {@code timed_out: true} and whatever it had collected so far, and one with a failed shard lists
 * it under {@code _shards.failed}. WF-SEARCH-004 and WF-SEARCH-011 both promise that such a result
 * is never used: the facet endpoint answers {@code SEARCH_TIMEOUT} and the count job keeps the
 * previous numbers. Both arrive as {@link IncompleteResultsException}, which the error handler
 * maps to {@code SEARCH_TIMEOUT} (docs/search/interfaces.md error catalog).
 */
class ProductSearchRepositoryAggregateTest {

    @Test
    void aTimedOutAggregationIsNotReturnedAsIfItWereComplete() throws IOException {
        ProductSearchRepository repository = repositoryAnswering(SearchResponse.<Void>of(b -> b
                .took(5000)
                .timedOut(true)
                .shards(s -> s.total(3).successful(3).failed(0))
                .hits(h -> h.hits(List.of()))));

        assertThatThrownBy(() -> repository.aggregate(Query.of(q -> q.matchAll(m -> m)), Map.of(), "category_facets"))
                .isInstanceOf(IncompleteResultsException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void anAggregationWithAFailedShardIsNotReturnedAsIfItWereComplete() throws IOException {
        ProductSearchRepository repository = repositoryAnswering(SearchResponse.<Void>of(b -> b
                .took(40)
                .timedOut(false)
                .shards(s -> s.total(3).successful(2).failed(1))
                .hits(h -> h.hits(List.of()))));

        assertThatThrownBy(() -> repository.aggregate(Query.of(q -> q.matchAll(m -> m)), Map.of(), "category_facets"))
                .isInstanceOf(IncompleteResultsException.class)
                .hasMessageContaining("incomplete shard results");
    }

    @Test
    void productGroupingWithAFailedShardIsNotReturnedAsIfItWereComplete() throws IOException {
        ProductSearchRepository repository = repositoryAnswering(SearchResponse.<Void>of(b -> b
                .took(40)
                .timedOut(false)
                .pitId("pit-1")
                .shards(s -> s.total(3).successful(2).failed(1))
                .hits(h -> h.hits(List.of()))));

        assertThatThrownBy(() -> repository.products(Query.of(q -> q.matchAll(m -> m)), "pit-1"))
                .isInstanceOf(IncompleteResultsException.class)
                .hasMessageContaining("incomplete shard results");
    }

    @SuppressWarnings("unchecked")
    private static ProductSearchRepository repositoryAnswering(SearchResponse<Void> answer) throws IOException {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.search(any(Function.class), eq(Void.class))).thenReturn(answer);

        SearchProperties properties = mock(SearchProperties.class);
        when(properties.aliases()).thenReturn(new SearchProperties.Aliases(
                "products", "products_write", "categories", "categories_write",
                "search_suggestions", "search_jobs"));
        when(properties.query()).thenReturn(new SearchProperties.QueryBudget(
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofMinutes(2),
                1000, 50, 100));

        return new ProductSearchRepository(client, properties,
                new SearchMetrics(new SimpleMeterRegistry()), mock(ProductQueryBuilder.class), "2m", 500, 50_000);
    }
}
