package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.domain.document.SuggestionDocument;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.dto.SuggestResponse;
import com.ecommerce.search.repository.SuggestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for the autocomplete/suggest API ({@code GET /api/v1/search/suggest}).
 *
 * <p>Validates the query, builds the ES query via {@link SuggestQueryBuilder},
 * executes it through {@link SuggestRepository}, and transforms the results
 * into a {@link SuggestResponse}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestService {

    private static final int MAX_LIMIT = 20;
    private static final int MIN_QUERY_LENGTH = 2;

    private final SuggestRepository repository;
    private final SuggestQueryBuilder queryBuilder;

    /**
     * Get autocomplete suggestions for the given query prefix.
     *
     * @param q     the user's current input (at least 2 characters)
     * @param limit max number of suggestions (default 8, max 20)
     * @return a {@link SuggestResponse} with ranked suggestions
     * @throws SearchException if {@code q} is too short (INVALID_QUERY_PARAMETER)
     */
    public SuggestResponse suggest(String q, Integer limit) {
        // 1. Validate query length
        if (q == null || q.trim().length() < MIN_QUERY_LENGTH) {
            throw new SearchException(ErrorCode.INVALID_QUERY_PARAMETER,
                "Query 'q' must have at least " + MIN_QUERY_LENGTH + " characters");
        }

        int actualLimit = (limit == null) ? 8 : Math.min(limit, MAX_LIMIT);
        String normalizedQ = q.trim().toLowerCase();

        log.debug("Suggest: q={}, limit={}", normalizedQ, actualLimit);

        // 2. Build and execute query
        SearchRequest request = queryBuilder.build(normalizedQ, actualLimit);
        SearchResponse<SuggestionDocument> response = repository.search(request);

        // 3. Transform hits to response DTO
        List<SuggestResponse.SuggestItem> items = response.hits().hits().stream()
            .map(hit -> hit.source())
            .filter(java.util.Objects::nonNull)
            .map(doc -> new SuggestResponse.SuggestItem(doc.text(), doc.type(), doc.weight()))
            .toList();

        log.debug("Suggest returned {} results for q={}", items.size(), normalizedQ);
        return new SuggestResponse(items);
    }
}
