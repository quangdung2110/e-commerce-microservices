package com.ecommerce.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.SuggestionDocument;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Reads of the pre-computed {@code search_suggestions} index.
 *
 * <p>Ranking is by the materialised {@code weight}, not by {@code _score}: a prefix either
 * matches or it does not, and the ordering afterwards is a business ranking computed at
 * build time (ADR-003).
 */
@Repository
public class SuggestionRepository {

    private final ElasticsearchClient client;
    private final SearchProperties properties;
    private final SearchMetrics metrics;
    private final com.ecommerce.search.config.StorefrontProperties storefront;

    public SuggestionRepository(ElasticsearchClient client, SearchProperties properties, SearchMetrics metrics) {
        this(client, properties, metrics, new com.ecommerce.search.config.StorefrontProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SuggestionRepository(ElasticsearchClient client, SearchProperties properties, SearchMetrics metrics,
                                com.ecommerce.search.config.StorefrontProperties storefront) {
        this.client = client;
        this.properties = properties;
        this.metrics = metrics;
        this.storefront = storefront;
    }

    public List<SuggestionDocument> suggest(String prefix, int limit) throws IOException {
        return suggestIn(properties.aliases().searchSuggestions(), prefix, limit);
    }

    /**
     * Same query against an explicit physical index — used by the rebuild validation gate,
     * which must exercise the real suggest query against the newly built index before the
     * alias is swapped.
     */
    public List<SuggestionDocument> suggestIn(String index, String prefix, int limit) throws IOException {
        long startedAt = System.nanoTime();
        SearchResponse<SuggestionDocument> response = client.search(builder -> builder
                .index(index)
                .size(limit)
                .trackTotalHits(track -> track.enabled(false))
                .query(publicQuery(prefix))
                .sort(sort -> sort.field(f -> f.field("weight").order(SortOrder.Desc)))
                .sort(sort -> sort.field(f -> f.field("text.keyword").order(SortOrder.Asc)))
                .allowPartialSearchResults(false)
                .timeout(properties.query().suggestTimeout().toMillis() + "ms"),
                SuggestionDocument.class);
        metrics.esQuery(index, "suggest", Duration.ofNanos(System.nanoTime() - startedAt));
        SearchCompleteness.require(response, "suggest");
        return response.hits().hits().stream().map(hit -> hit.source()).filter(Objects::nonNull).toList();
    }

    /**
     * The index analyser is an edge n-gram and the search analyser is not, so a plain
     * {@code match} is what performs prefix completion here.
     */
    private static Query matchPrefix(String prefix) {
        return Query.of(q -> q.match(match -> match.field("text").query(prefix)));
    }

    Query publicQuery(String prefix) {
        return Query.of(q -> q.bool(b -> {
            b.must(matchPrefix(prefix));
            if (!storefront.getExcludedProductIds().isEmpty()) b.mustNot(n -> n.terms(t -> t.field("product_id")
                    .terms(v -> v.value(storefront.getExcludedProductIds().stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()))));
            if (!storefront.getExcludedCategoryIds().isEmpty()) b.mustNot(n -> n.terms(t -> t.field("category_id")
                    .terms(v -> v.value(storefront.getExcludedCategoryIds().stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()))));
            return b;
        }));
    }
}
