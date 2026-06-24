package com.ecommerce.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.json.JsonData;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.exception.SearchException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.Map;

/**
 * Repository for category operations on the {@code categories} alias.
 *
 * <p>Provides lookup by category ID, indexing, and script-based updates
 * (primarily for real-time {@code product_count} adjustments).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CategoryRepository {

    private final ElasticsearchClient esClient;
    private final SearchProperties properties;

    /**
     * Retrieve a category document by its category ID.
     *
     * @param categoryId the category identifier (document _id)
     * @return the CategoryDocument if found, or {@code null} otherwise
     * @throws SearchException on connection errors
     */
    public CategoryDocument getById(String categoryId) {
        try {
            GetResponse<CategoryDocument> response = esClient.get(g -> g
                    .index(properties.getEs().getIndexAliases().getCategories())
                    .id(categoryId),
                CategoryDocument.class
            );
            return response.found() ? response.source() : null;
        } catch (IOException | RuntimeException e) {
            log.error("Failed to get category by id={}", categoryId, e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE,
                "ES get failed for categoryId=" + categoryId, e);
        }
    }

    /**
     * Execute a search request against the {@code categories} alias.
     *
     * @param request the fully-built ES SearchRequest
     * @return the search response containing hits of {@link CategoryDocument}
     * @throws SearchException with {@link ErrorCode#ELASTICSEARCH_UNAVAILABLE} on connection errors
     */
    public SearchResponse<CategoryDocument> search(SearchRequest request) {
        try {
            return esClient.search(request, CategoryDocument.class);
        } catch (IOException | RuntimeException e) {
            log.error("Failed to search categories", e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES categories search failed", e);
        }
    }

    /**
     * Index (create or replace) a category document into the {@code categories_write} alias.
     *
     * <p>Used by event handlers for {@code CATEGORY_CREATED} and {@code CATEGORY_UPDATED}.
     *
     * @param doc the CategoryDocument to index
     * @throws SearchException on write failures
     */
    public void index(CategoryDocument doc) {
        try {
            IndexResponse response = esClient.index(i -> i
                .index(properties.getEs().getIndexAliases().getCategoriesWrite())
                .id(doc.categoryId())
                .document(doc)
            );
            log.debug("Indexed category {}: result={}", doc.categoryId(), response.result());
        } catch (IOException | RuntimeException e) {
            log.error("Failed to index category {}", doc.categoryId(), e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE,
                "ES index failed for categoryId=" + doc.categoryId(), e);
        }
    }

    /**
     * Partial update using a Painless script on the category document.
     *
     * <p>Primarily used for real-time {@code product_count} adjustments via
     * {@code _update_by_query} (Module 09).
     *
     * @param categoryId   the category identifier (document _id)
     * @param scriptSource the Painless script source
     * @param params       parameters available as {@code params.*} in the script
     * @throws SearchException with {@link ErrorCode#INTERNAL_ERROR} if document not found
     * @throws SearchException with {@link ErrorCode#ELASTICSEARCH_UNAVAILABLE} on other failures
     */
    public void updateByScript(String categoryId, String scriptSource, Map<String, Object> params) {
        try {
            // Convert Map<String, Object> to Map<String, JsonData> (ES Java API Client requirement)
            Map<String, JsonData> esParams = params.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                    java.util.Map.Entry::getKey,
                    e -> JsonData.of(e.getValue())
                ));

            UpdateResponse<CategoryDocument> response = esClient.update(u -> u
                    .index(properties.getEs().getIndexAliases().getCategoriesWrite())
                    .id(categoryId)
                    .script(s -> s.inline(i -> i
                        .source(scriptSource)
                        .params(esParams)
                    ))
                    .retryOnConflict(3),
                CategoryDocument.class
            );
            log.debug("Updated category {}: result={}", categoryId, response.result());
        } catch (IOException | RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("document_missing_exception")) {
                log.warn("Document missing for category {}: {}", categoryId, msg);
                throw new SearchException(ErrorCode.INTERNAL_ERROR,
                    "Document missing for categoryId=" + categoryId, e);
            }
            log.error("Failed to update category {}", categoryId, e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE,
                "ES update failed for categoryId=" + categoryId, e);
        }
    }
}
