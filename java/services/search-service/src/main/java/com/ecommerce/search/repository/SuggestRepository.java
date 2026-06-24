package com.ecommerce.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.SuggestionDocument;
import com.ecommerce.search.domain.exception.SearchException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.List;

/**
 * Repository for suggest (autocomplete) operations on the {@code search_suggestions} alias.
 *
 * <p>This index is rebuilt atomically every day by the {@code SuggestionRebuilder}.
 * It has no write alias — the rebuilder writes directly to a versioned index and
 * performs an atomic alias swap.
 *
 * <p>Because the index has no partial updates, only bulk-index and search operations
 * are provided here.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SuggestRepository {

    private final ElasticsearchClient esClient;
    private final SearchProperties properties;

    /**
     * Execute a search request against the {@code search_suggestions} alias.
     *
     * @param request the fully-built ES SearchRequest
     * @return the search response with hits of {@link SuggestionDocument}
     * @throws SearchException on search failures
     */
    public SearchResponse<SuggestionDocument> search(SearchRequest request) {
        try {
            return esClient.search(request, SuggestionDocument.class);
        } catch (IOException | RuntimeException e) {
            log.error("Failed to search suggestions", e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES suggest search failed", e);
        }
    }

    /**
     * Bulk index suggestion documents into the given index name (not alias, since
     * the suggestions index has no write alias).
     *
     * <p>Used by the {@code SuggestionRebuilder} to populate a new versioned index
     * before the atomic alias swap.
     *
     * @param indexName the target index name (e.g. "search_suggestions_v2")
     * @param docs      the list of SuggestionDocuments to index
     * @throws SearchException on bulk write failures
     */
    public void bulkIndexToIndex(String indexName, List<SuggestionDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        try {
            BulkRequest.Builder br = new BulkRequest.Builder();
            for (SuggestionDocument doc : docs) {
                br.operations(op -> op.index(idx -> idx
                    .index(indexName)
                    .id(doc.suggestionId())
                    .document(doc)
                ));
            }
            BulkResponse response = esClient.bulk(br.refresh(Refresh.WaitFor).build());
            if (response.errors()) {
                long failureCount = response.items().stream()
                    .filter(i -> i.error() != null)
                    .count();
                log.warn("Bulk index suggestions to [{}] completed with errors: total={}, failed={}",
                    indexName, response.items().size(), failureCount);
            } else {
                log.debug("Bulk indexed {} suggestions to [{}]", docs.size(), indexName);
            }
        } catch (IOException | RuntimeException e) {
            log.error("Failed to bulk index {} suggestions to [{}]", docs.size(), indexName, e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES suggest bulk failed", e);
        }
    }

    /**
     * Bulk index suggestion documents into the given index name (not alias, since
     * the suggestions index has no write alias).
     *
     * <p>Used by the {@code SuggestionRebuilder} to populate a new versioned index
     * before the atomic alias swap.
     *
     * @param indexName the target index name (e.g. "search_suggestions_v2")
     * @param docs      the list of SuggestionDocuments to index
     * @throws SearchException on bulk write failures
     */
    public void bulkIndex(String indexName, List<SuggestionDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        try {
            BulkRequest.Builder br = new BulkRequest.Builder();
            for (SuggestionDocument doc : docs) {
                br.operations(op -> op.index(idx -> idx
                    .index(indexName)
                    .id(doc.suggestionId())
                    .document(doc)
                ));
            }
            BulkResponse response = esClient.bulk(br.build());
            if (response.errors()) {
                long failureCount = response.items().stream()
                    .filter(i -> i.error() != null)
                    .count();
                log.warn("Bulk index suggestions completed with errors: total={}, failed={}",
                    response.items().size(), failureCount);
            } else {
                log.debug("Bulk indexed {} suggestions successfully", docs.size());
            }
        } catch (IOException | RuntimeException e) {
            log.error("Failed to bulk index {} suggestions", docs.size(), e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES suggest bulk failed", e);
        }
    }
}
