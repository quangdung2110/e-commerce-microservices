package com.ecommerce.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.exception.SearchException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Repository for product search and indexing operations on the {@code products} alias.
 *
 * <p>Provides basic CRUD operations — search, get by SKU ID, index, update by
 * Painless script, delete, and bulk index. All public methods throw
 * {@link SearchException} with appropriate {@link ErrorCode} on failure,
 * allowing higher-level components (EventHandlers, Services) to handle errors
 * consistently.
 *
 * <p><strong>document_missing_exception handling:</strong> The {@code updateByScript}
 * method catches {@code document_missing_exception} (404) and rethrows it as
 * {@link SearchException} with {@link ErrorCode#INTERNAL_ERROR}. This is
 * intentional — the caller (e.g. EventHandler in Module 08) is expected to
 * handle the missing document case (log + skip + ACK).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ProductSearchRepository {

    private final ElasticsearchClient esClient;
    private final SearchProperties properties;

    /**
     * Execute a search request against the {@code products} alias.
     *
     * @param request the fully-built ES SearchRequest
     * @return the search response containing hits of {@link ProductDocument}
     * @throws SearchException with {@link ErrorCode#ELASTICSEARCH_UNAVAILABLE} on
     *         connection errors, or {@link ErrorCode#SEARCH_TIMEOUT} on timeout
     */
    public SearchResponse<ProductDocument> search(SearchRequest request) {
        try {
            return esClient.search(request, ProductDocument.class);
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("timed out") || msg.contains("Timeout"))) {
                log.error("ES search timed out", e);
                throw new SearchException(ErrorCode.SEARCH_TIMEOUT, "ES search timed out", e);
            }
            log.error("ES search failed", e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES search failed", e);
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            System.err.println("ES ERROR REASON: " + e.error().reason());
            if (e.error().rootCause() != null && !e.error().rootCause().isEmpty()) {
                System.err.println("ES ROOT CAUSE: " + e.error().rootCause().get(0).reason());
            }
            e.printStackTrace();
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES search failed", e);
        } catch (RuntimeException e) {
            log.error("ES search failed with unexpected error", e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES search failed", e);
        }
    }

    /**
     * Retrieve a product document by its SKU ID.
     *
     * @param skuId the SKU identifier (document _id)
     * @return the ProductDocument if found, or {@code null} if not exists
     * @throws SearchException on connection errors
     */
    public ProductDocument getBySkuId(String skuId) {
        try {
            GetResponse<ProductDocument> response = esClient.get(g -> g
                    .index(properties.getEs().getIndexAliases().getProducts())
                    .id(skuId),
                ProductDocument.class
            );
            return response.found() ? response.source() : null;
        } catch (IOException | RuntimeException e) {
            log.error("Failed to get product by skuId={}", skuId, e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES get failed for skuId=" + skuId, e);
        }
    }

    /**
     * Index (create or replace) a product document into the {@code products_write} alias.
     *
     * @param doc the ProductDocument to index
     * @throws SearchException on write failures
     */
    public void index(ProductDocument doc) {
        try {
            IndexResponse response = esClient.index(i -> i
                .index(properties.getEs().getIndexAliases().getProductsWrite())
                .id(doc.skuId())
                .document(doc)
            );
            log.debug("Indexed SKU {}: result={}", doc.skuId(), response.result());
        } catch (IOException | RuntimeException e) {
            log.error("Failed to index SKU {}", doc.skuId(), e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES index failed for skuId=" + doc.skuId(), e);
        }
    }

    /**
     * Partial update using a Painless script on the document with the given SKU ID.
     *
     * <p>This is the primary method for handling {@code PRODUCT_UPDATED},
     * {@code SKU_UPDATED}, and tombstone updates from {@code PRODUCT_DELETED}.
     *
     * <p><strong>document_missing_exception:</strong> If the document does not exist,
     * the method throws {@link SearchException} with {@link ErrorCode#INTERNAL_ERROR}.
     * The caller should catch this, log, increment metric, and ACK the event.
     *
     * @param skuId        the SKU identifier (document _id)
     * @param scriptSource the Painless script source
     * @param params       parameters available as {@code params.*} in the script
     * @throws SearchException with {@link ErrorCode#INTERNAL_ERROR} if document not found
     * @throws SearchException with {@link ErrorCode#ELASTICSEARCH_UNAVAILABLE} on other failures
     */
    public void updateByScript(String skuId, String scriptSource, Map<String, Object> params) {
        try {
            // Convert Map<String, Object> to Map<String, JsonData> (ES Java API Client requirement)
            Map<String, JsonData> esParams = params.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                    java.util.Map.Entry::getKey,
                    e -> JsonData.of(e.getValue())
                ));

            UpdateResponse<ProductDocument> response = esClient.update(u -> u
                    .index(properties.getEs().getIndexAliases().getProductsWrite())
                    .id(skuId)
                    .script(s -> s.inline(i -> i
                        .source(scriptSource)
                        .params(esParams)
                    ))
                    .retryOnConflict(3),
                ProductDocument.class
            );
            log.debug("Updated SKU {}: result={}", skuId, response.result());
        } catch (IOException | RuntimeException e) {
            String msg = e.getMessage();
            // document_missing_exception (404) — document does not exist
            if (msg != null && msg.contains("document_missing_exception")) {
                log.warn("Document missing for SKU {}: {}", skuId, msg);
                throw new SearchException(ErrorCode.INTERNAL_ERROR, "Document missing for skuId=" + skuId, e);
            }
            log.error("Failed to update SKU {}", skuId, e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES update failed for skuId=" + skuId, e);
        }
    }

    /**
     * Hard-delete a document by SKU ID from the {@code products_write} alias.
     *
     * <p>Used by the DraftCleanupJob (Module 12) to permanently remove documents
     * past the tombstone retention period. For soft-delete (tombstone marking),
     * use {@link #updateByScript} instead.
     *
     * @param skuId the SKU identifier (document _id) to delete
     * @throws SearchException on delete failures
     */
    public void deleteBySkuId(String skuId) {
        try {
            DeleteResponse response = esClient.delete(d -> d
                .index(properties.getEs().getIndexAliases().getProductsWrite())
                .id(skuId)
            );
            if (response.result() == Result.Deleted) {
                log.debug("Deleted SKU {}", skuId);
            } else {
                log.warn("Delete SKU {} returned result={}", skuId, response.result());
            }
        } catch (IOException | RuntimeException e) {
            log.error("Failed to delete SKU {}", skuId, e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES delete failed for skuId=" + skuId, e);
        }
    }

    /**
     * Bulk index multiple product documents in a single request.
     *
     * <p>Used during full reindex (Module 10) and suggestions rebuild.
     * Logs a warning if any individual operation within the bulk fails.
     *
     * @param docs the list of ProductDocuments to index
     * @throws SearchException on bulk request failures
     */
    public void bulkIndex(List<ProductDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        try {
            BulkRequest.Builder br = new BulkRequest.Builder();
            for (ProductDocument doc : docs) {
                br.operations(op -> op.index(idx -> idx
                    .index(properties.getEs().getIndexAliases().getProductsWrite())
                    .id(doc.skuId())
                    .document(doc)
                ));
            }
            BulkResponse response = esClient.bulk(br.build());
            if (response.errors()) {
                long failureCount = response.items().stream()
                    .filter(i -> i.error() != null)
                    .count();
                log.warn("Bulk index completed with errors: total={}, failed={}",
                    response.items().size(), failureCount);
            } else {
                log.debug("Bulk indexed {} documents successfully", docs.size());
            }
        } catch (IOException | RuntimeException e) {
            log.error("Failed to bulk index {} documents", docs.size(), e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES bulk failed", e);
        }
    }

    /**
     * Hard-delete documents matching the given query from the products index.
     *
     * <p>Used by the {@code TombstonePurger} (Module 12) to permanently remove
     * documents past the tombstone retention period. Uses {@code conflicts=proceed}
     * to handle concurrent updates gracefully.
     *
     * @param query the query matching documents to delete
     * @return the ES response with deletion counts
     * @throws SearchException with {@link ErrorCode#ELASTICSEARCH_UNAVAILABLE} on failures
     */
    public co.elastic.clients.elasticsearch.core.DeleteByQueryResponse deleteByQuery(Query query) {
        try {
            co.elastic.clients.elasticsearch.core.DeleteByQueryResponse response = esClient.deleteByQuery(d -> d
                .index(properties.getEs().getIndexAliases().getProducts())
                .query(query)
                .refresh(true)
                .conflicts(Conflicts.Proceed)
            );
            log.info("deleteByQuery: deleted={}, total={}, failures={}",
                response.deleted(), response.total(),
                response.failures() != null ? response.failures().size() : 0);
            return response;
        } catch (IOException | RuntimeException e) {
            log.error("Failed to delete_by_query", e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES deleteByQuery failed", e);
        }
    }

    /**
     * Execute an {@code _update_by_query} for all SKU documents sharing the
     * given {@code product_id}.
     *
     * <p>Used by {@link ProductEventHandlers} when processing
     * {@code product_status_changed} via {@code SKU_UPDATED} (Phase 19):
     * the Painless script updates {@code product_status} and recomputes
     * {@code is_in_stock} across every SKU of the product in one round-trip.
     *
     * <p>Throttle is set via {@link SearchProperties#getEs().getUpdateByQueryThrottleRps()}
     * (default 300 rps) and {@code conflicts=proceed} to handle concurrent updates.
     *
     * @param productId    the product whose SKU documents should be updated
     * @param scriptSource the Painless script source
     * @param params       parameters injected into the script as {@code params.*}
     * @throws SearchException with {@link ErrorCode#ELASTICSEARCH_UNAVAILABLE} on failures
     */
    public void updateByQueryForProduct(String productId, String scriptSource, Map<String, Object> params) {
        try {
            Query query = Query.of(q -> q.term(t -> t.field("product_id").value(productId)));

            // Convert params to ES JsonData
            Map<String, JsonData> esParams = params.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                    java.util.Map.Entry::getKey,
                    e -> JsonData.of(e.getValue())
                ));

            co.elastic.clients.elasticsearch.core.UpdateByQueryResponse response = esClient.updateByQuery(u -> u
                .index(properties.getEs().getIndexAliases().getProductsWrite())
                .query(query)
                .script(s -> s.inline(i -> i
                    .source(scriptSource)
                    .params(esParams)
                ))
                .refresh(true)
                .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
                .requestsPerSecond((float) properties.getEs().getUpdateByQueryThrottleRps())
            );

            int failureCount = response.failures() != null ? response.failures().size() : 0;
            log.info("updateByQueryForProduct: product_id={}, updated={}, total={}, failures={}",
                productId, response.updated(), response.total(), failureCount);

        } catch (IOException e) {
            log.error("updateByQueryForProduct failed for product_id={}", productId, e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE,
                "ES updateByQuery failed for productId=" + productId, e);
        } catch (RuntimeException e) {
            log.error("updateByQueryForProduct failed for product_id={}", productId, e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE,
                "ES updateByQuery failed for productId=" + productId, e);
        }
    }
}
