package com.ecommerce.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.cluster.HealthRequest;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.indices.*;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.exception.SearchException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Repository for full reindex operations (Module 10).
 *
 * <p>Provides index-level operations needed by the 3-phase reindex job:
 * <ol>
 *   <li><strong>Build:</strong> Create a new versioned index and bulk-index data</li>
 *   <li><strong>Catch-up:</strong> Apply Kafka backlog to the new index</li>
 *   <li><strong>Cutover:</strong> Atomic alias swap + cleanup old index</li>
 * </ol>
 *
 * <p>All operations target concrete index names (e.g. "products_v2"), not aliases,
 * except for {@link #swapAlias} and {@link #resolveIndexForAlias} which manage
 * the alias-to-index mapping.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ReindexRepository {

    private final ElasticsearchClient esClient;

    /**
     * Create a new Elasticsearch index with the given mapping.
     *
     * @param newIndexName the new index name (e.g. "products_v2")
     * @param mappingJson  the complete index settings + mapping JSON
     * @throws SearchException if the index already exists or creation fails
     */
    public void createIndex(String newIndexName, String mappingJson) {
        try {
            boolean exists = esClient.indices()
                .exists(ExistsRequest.of(b -> b.index(newIndexName)))
                .value();
            if (exists) {
                log.warn("Index [{}] already exists, will not re-create", newIndexName);
                return;
            }
            esClient.indices().create(CreateIndexRequest.of(b -> b
                .index(newIndexName)
                .withJson(new StringReader(mappingJson))
            ));
            log.info("Created reindex index [{}]", newIndexName);
        } catch (ElasticsearchException | IOException e) {
            log.error("Failed to create reindex index [{}]", newIndexName, e);
            throw new SearchException(ErrorCode.INTERNAL_ERROR,
                "Failed to create index " + newIndexName, e);
        }
    }

    /**
     * Bulk-index documents into a specific versioned index (not through an alias).
     *
     * <p>Used during the Build phase to populate the new index from the product-service
     * internal API or from cloning the existing index.
     *
     * @param indexName the target index name (e.g. "products_v2")
     * @param docs      the list of ProductDocuments to index
     * @throws SearchException on bulk write failures
     */
    public void bulkIndexToVersioned(String indexName, List<ProductDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        try {
            BulkRequest.Builder br = new BulkRequest.Builder();
            for (ProductDocument doc : docs) {
                br.operations(op -> op.index(idx -> idx
                    .index(indexName)
                    .id(doc.skuId())
                    .document(doc)
                ));
            }
            BulkResponse response = esClient.bulk(br.build());
            if (response.errors()) {
                long failureCount = response.items().stream()
                    .filter(i -> i.error() != null)
                    .count();
                log.warn("Bulk index to [{}] completed with errors: total={}, failed={}",
                    indexName, response.items().size(), failureCount);
            } else {
                log.debug("Bulk indexed {} documents to [{}]", docs.size(), indexName);
            }
        } catch (IOException | RuntimeException e) {
            log.error("Failed to bulk index {} documents to [{}]", docs.size(), indexName, e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE,
                "ES bulk failed for index=" + indexName, e);
        }
    }

    /**
     * Clone all documents from a source index/alias to a target index using
     * Elasticsearch's {@code _reindex} API.
     *
     * <p>This is the primary mechanism for the Build phase of a full reindex:
     * it copies every document from the current alias (e.g. {@code products})
     * to the new versioned index (e.g. {@code products_v2}) in a single
     * cluster-managed operation.
     *
     * <p>For very large indices the operation is throttled by the ES cluster;
     * the caller should monitor {@code total} in the response to verify
     * completion.
     *
     * @param sourceAlias the source alias or index name
     * @param targetIndex the target index name
     * @return the total number of documents cloned
     * @throws SearchException if the reindex operation fails
     */
    public long cloneIndex(String sourceAlias, String targetIndex) {
        try {
            var response = esClient.reindex(r -> r
                .source(s -> s.index(sourceAlias))
                .dest(d -> d.index(targetIndex))
                .refresh(true)
            );
            log.info("Reindex clone from [{}] to [{}]: total={}, created={}, updated={}, failures={}",
                sourceAlias, targetIndex,
                response.total(), response.created(), response.updated(),
                response.failures() != null ? response.failures().size() : 0);
            return response.total();
        } catch (ElasticsearchException | IOException e) {
            log.error("Failed to clone index from [{}] to [{}]", sourceAlias, targetIndex, e);
            throw new SearchException(ErrorCode.INTERNAL_ERROR,
                "Failed to clone index from " + sourceAlias + " to " + targetIndex, e);
        }
    }

    /**
     * Add an alias to an index (used when no previous alias exists, such as
     * the first time a suggestions index is created).
     *
     * @param indexName the index to alias
     * @param aliasName the alias name to add
     * @throws SearchException if the operation fails
     */
    public void addAlias(String indexName, String aliasName) {
        try {
            esClient.indices().updateAliases(b -> b
                .actions(a -> a.add(add -> add.index(indexName).alias(aliasName)))
            );
            log.info("Added alias [{}] -> [{}]", aliasName, indexName);
        } catch (ElasticsearchException | IOException e) {
            log.error("Failed to add alias [{}] to index [{}]", aliasName, indexName, e);
            throw new SearchException(ErrorCode.INTERNAL_ERROR,
                "Failed to add alias " + aliasName + " to index " + indexName, e);
        }
    }

    /**
     * Atomically swap aliases from the old index to the new index.
     *
     * <p>Uses Elasticsearch's {@code _alias} atomic operation: removes the alias
     * from the old index and adds it to the new index in a single request.
     *
     * <p>Both the read alias and write alias are swapped simultaneously. After
     * this operation completes, all traffic (read + write) goes to the new index.
     *
     * <p>If {@code writeAliasName} is {@code null} (e.g. for the suggestions
     * index which has no write alias), only the read alias is swapped.
     *
     * @param oldIndexName   the current index (e.g. "products_v1")
     * @param newIndexName   the target index (e.g. "products_v2")
     * @param aliasName      the read alias (e.g. "products")
     * @param writeAliasName the write alias (e.g. "products_write"), or {@code null}
     *                       for indices without a separate write alias
     * @throws SearchException if the alias swap fails
     */
    public void swapAlias(String oldIndexName, String newIndexName,
                          String aliasName, String writeAliasName) {
        try {
            var builder = new co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest.Builder();

            // Remove read alias from old index
            builder.actions(a -> a.remove(r -> r.index(oldIndexName).alias(aliasName)));
            // Add read alias to new index
            builder.actions(a -> a.add(add -> add.index(newIndexName).alias(aliasName).isWriteIndex(false)));

            if (writeAliasName != null) {
                // Remove write alias from old index
                builder.actions(a -> a.remove(r -> r.index(oldIndexName).alias(writeAliasName)));
                // Add write alias to new index
                builder.actions(a -> a.add(add -> add.index(newIndexName).alias(writeAliasName).isWriteIndex(true)));
            }

            esClient.indices().updateAliases(builder.build());
            log.info("Alias swap complete: [{}] and [{}] now point to [{}]",
                aliasName, writeAliasName, newIndexName);
        } catch (ElasticsearchException | IOException e) {
            log.error("Failed to swap aliases from [{}] to [{}]", oldIndexName, newIndexName, e);
            throw new SearchException(ErrorCode.INTERNAL_ERROR,
                "Failed to swap aliases", e);
        }
    }

    /**
     * Delete an index (for cleanup after successful reindex or rollback).
     *
     * @param indexName the index to delete
     * @throws SearchException if deletion fails
     */
    public void deleteIndex(String indexName) {
        try {
            boolean exists = esClient.indices()
                .exists(ExistsRequest.of(b -> b.index(indexName)))
                .value();
            if (!exists) {
                log.warn("Index [{}] does not exist, skipping delete", indexName);
                return;
            }
            esClient.indices().delete(DeleteIndexRequest.of(b -> b.index(indexName)));
            log.info("Deleted index [{}]", indexName);
        } catch (ElasticsearchException | IOException e) {
            log.error("Failed to delete index [{}]", indexName, e);
            throw new SearchException(ErrorCode.INTERNAL_ERROR,
                "Failed to delete index " + indexName, e);
        }
    }

    /**
     * Count documents in the given index.
     *
     * @param indexName the index name
     * @return the document count
     * @throws SearchException if the count query fails
     */
    public long countDocs(String indexName) {
        try {
            CountResponse response = esClient.count(c -> c.index(indexName));
            return response.count();
        } catch (ElasticsearchException | IOException e) {
            log.error("Failed to count docs in index [{}]", indexName, e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE,
                "Failed to count docs in index " + indexName, e);
        }
    }

    /**
     * Resolve the concrete index name that an alias currently points to.
     *
     * <p>Used to find the current write index before initiating a reindex, and
     * to determine the old index to roll back to if the reindex fails.
     *
     * @param aliasName the alias name (e.g. "products_write")
     * @return the concrete index name, or {@code null} if the alias doesn't exist
     * @throws SearchException if resolution fails
     */
    public String resolveIndexForAlias(String aliasName) {
        try {
            var getAliasResponse = esClient.indices().getAlias(
                GetAliasRequest.of(b -> b.name(aliasName))
            );
            // Result maps index name -> alias metadata
            Set<String> indices = getAliasResponse.result().keySet();
            if (indices.isEmpty()) {
                return null;
            }
            // An alias typically points to one index at a time
            String indexName = indices.iterator().next();
            log.debug("Alias [{}] resolves to index [{}]", aliasName, indexName);
            return indexName;
        } catch (ElasticsearchException | IOException e) {
            // 404 — alias not found
            if (e instanceof ElasticsearchException) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("404")) {
                    log.warn("Alias [{}] not found", aliasName);
                    return null;
                }
            }
            log.error("Failed to resolve alias [{}]", aliasName, e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE,
                "Failed to resolve alias " + aliasName, e);
        }
    }
}
