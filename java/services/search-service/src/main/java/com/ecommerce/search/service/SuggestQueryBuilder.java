package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.ecommerce.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds the Elasticsearch {@link SearchRequest} for the suggest (autocomplete)
 * API ({@code GET /api/v1/search/suggest}).
 *
 * <p>Queries the {@code search_suggestions} index with a simple match query.
 * Results are pre-ranked via the {@code weight} field, which is computed
 * daily by the Suggestions Rebuilder (Module 11).
 *
 * <p>Only the {@code text}, {@code type}, and {@code weight} fields are
 * fetched; the full suggestion document is not returned to the client.
 */
@Component
@RequiredArgsConstructor
public class SuggestQueryBuilder {

    private final SearchProperties properties;

    /**
     * Build a search request for autocomplete suggestions.
     *
     * @param q     the user's current input prefix (at least 2 characters)
     * @param limit max number of suggestions to return (default 8, max 20)
     * @return a fully-built Elasticsearch SearchRequest
     */
    public SearchRequest build(String q, int limit) {
        return SearchRequest.of(s -> s
            .index(properties.getEs().getIndexAliases().getSuggestions())
            .query(Query.of(qb -> qb.matchBoolPrefix(m -> m.field("text").query(q))))
            .sort(List.of(
                SortOptions.of(so -> so.field(f -> f.field("weight").order(SortOrder.Desc))),
                SortOptions.of(so -> so.field(f -> f.field("text.keyword").order(SortOrder.Asc)))
            ))
            .size(limit)
            .source(src -> src.filter(f -> f.includes("text", "type", "weight")))
        );
    }
}
