package com.ecommerce.search.repository.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.ecommerce.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.StringReader;

/**
 * Initializes Elasticsearch indices and aliases on application startup.
 *
 * <p>Creates the three required indices ({@code products_v1}, {@code categories_v1},
 * {@code search_suggestions_v1}) with their respective mappings if they do not
 * already exist. Sets up the corresponding read/write aliases for zero-downtime
 * reindex support.
 *
 * <p><strong>Idempotent:</strong> Running this component multiple times will not
 * drop or alter existing indices. It only creates what is missing.
 *
 * <p><strong>Production safety:</strong> Never drops existing indices. For index
 * mapping changes, create a new versioned index (e.g. {@code products_v2}) and
 * swap aliases via the ReindexJob.
 */
@Slf4j
@Component
@Order(1)
@Profile("!test")
@RequiredArgsConstructor
public class IndexInitializer implements ApplicationRunner {

    private final ElasticsearchClient esClient;
    private final SearchProperties properties;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Initializing Elasticsearch indices...");

        // --- Products index ---
        createIndexIfNotExists("products_v1", ProductIndexMapping.MAPPING_JSON);
        ensureAlias("products_v1", properties.getEs().getIndexAliases().getProducts(), false);
        ensureAlias("products_v1", properties.getEs().getIndexAliases().getProductsWrite(), true);

        // --- Categories index ---
        createIndexIfNotExists("categories_v1", CategoryIndexMapping.MAPPING_JSON);
        ensureAlias("categories_v1", properties.getEs().getIndexAliases().getCategories(), false);
        ensureAlias("categories_v1", properties.getEs().getIndexAliases().getCategoriesWrite(), true);

        // --- Search suggestions index (read alias only — no write alias) ---
        createIndexIfNotExists("search_suggestions_v1", SuggestionIndexMapping.MAPPING_JSON);
        ensureAlias("search_suggestions_v1", properties.getEs().getIndexAliases().getSuggestions(), false);

        log.info("Elasticsearch index initialization completed");
    }

    /**
     * Creates an index with the given name and mapping JSON if it does not already exist.
     *
     * @param indexName  the index name (e.g. "products_v1")
     * @param mappingJson the complete JSON payload (settings + mappings)
     */
    private void createIndexIfNotExists(String indexName, String mappingJson) {
        try {
            boolean exists = esClient.indices()
                .exists(ExistsRequest.of(b -> b.index(indexName)))
                .value();

            if (exists) {
                log.info("Index [{}] already exists, skipping creation", indexName);
                return;
            }

            esClient.indices().create(CreateIndexRequest.of(b -> b
                .index(indexName)
                .withJson(new StringReader(mappingJson))
            ));
            log.info("Created index [{}]", indexName);
        } catch (Exception e) {
            log.error("Failed to create index [{}]", indexName, e);
            throw new RuntimeException("Failed to create index " + indexName, e);
        }
    }

    /**
     * Ensures that an alias exists for the given index, creating it if necessary.
     *
     * @param indexName the name of the index the alias should point to
     * @param aliasName the alias name (e.g. "products", "products_write")
     * @param isWriteIndex whether this alias should be marked as a write index
     */
    private void ensureAlias(String indexName, String aliasName, boolean isWriteIndex) {
        try {
            // Check if the alias already exists on ANY index
            boolean aliasExists = esClient.indices()
                .existsAlias(b -> b.name(aliasName))
                .value();

            if (aliasExists) {
                log.info("Alias [{}] already exists, skipping creation", aliasName);
                return;
            }

            esClient.indices().updateAliases(b -> b
                .actions(a -> a.add(add -> add
                    .index(indexName)
                    .alias(aliasName)
                    .isWriteIndex(isWriteIndex)
                ))
            );
            log.info("Created alias [{}] -> [{}] (isWriteIndex={})", aliasName, indexName, isWriteIndex);
        } catch (Exception e) {
            log.error("Failed to create alias [{}]", aliasName, e);
            throw new RuntimeException("Failed to create alias " + aliasName, e);
        }
    }
}
