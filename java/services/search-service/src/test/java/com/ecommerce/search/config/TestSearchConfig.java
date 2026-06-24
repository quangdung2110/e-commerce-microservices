package com.ecommerce.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;

import java.io.StringReader;

/**
 * Test configuration that replaces {@link com.ecommerce.search.repository.es.IndexInitializer}
 * with a version that uses simplified Elasticsearch index mappings.
 *
 * <p>The production mappings require the {@code analysis-icu} Elasticsearch plugin
 * for Vietnamese text analysis ({@code icu_tokenizer} + {@code icu_folding}),
 * which is not available in the standard Testcontainers ES Docker image.
 *
 * <p>This test configuration creates indices with a standard analyzer instead,
 * which is sufficient for integration test purposes.
 */
@Slf4j
@Profile("test")
@TestConfiguration
public class TestSearchConfig {

    /**
     * Simplified index settings/mapping for products (no ICU plugin required).
     * Uses {@code standard} analyzer instead of {@code icu_tokenizer} +
     * {@code icu_folding}, and replaces the autocomplete edge_ngram with a
     * simpler configuration.
     */
    private static final String PRODUCT_MAPPING_JSON = """
        {
          "settings": {
            "number_of_shards": 1,
            "number_of_replicas": 0,
            "refresh_interval": "1s",
            "analysis": {
              "analyzer": {
                "vietnamese_analyzer": {
                  "type": "custom",
                  "tokenizer": "standard",
                  "filter": ["lowercase"]
                },
                "autocomplete_analyzer": {
                  "type": "custom",
                  "tokenizer": "autocomplete_tokenizer",
                  "filter": ["lowercase"]
                },
                "autocomplete_search_analyzer": {
                  "type": "custom",
                  "tokenizer": "standard",
                  "filter": ["lowercase"]
                }
              },
              "tokenizer": {
                "autocomplete_tokenizer": {
                  "type": "edge_ngram",
                  "min_gram": 2,
                  "max_gram": 20,
                  "token_chars": ["letter", "digit"]
                }
              }
            }
          },
          "mappings": {
            "properties": {
              "sku_id":                { "type": "keyword" },
              "product_id":            { "type": "keyword" },
              "category_id":           { "type": "keyword" },
              "category_name":         { "type": "keyword" },
              "category_path":         { "type": "keyword" },
              "name": {
                "type": "text",
                "analyzer": "vietnamese_analyzer",
                "fields": {
                  "keyword": { "type": "keyword" }
                }
              },
              "description":           { "type": "text", "analyzer": "vietnamese_analyzer" },
              "thumbnail_url":         { "type": "keyword" },
              "product_status":        { "type": "keyword" },
              "sku_status":            { "type": "keyword" },
              "searchable_attributes": { "type": "keyword" },
              "variant_attributes":    { "type": "keyword" },
              "original_price":        { "type": "long" },
              "promotional_price":     { "type": "long" },
              "best_price":            { "type": "long" },
              "is_in_stock":           { "type": "boolean" },
              "has_promotion":         { "type": "boolean" },
              "is_deleted":            { "type": "boolean" },
              "deleted_at":            { "type": "date" },
              "product_rating_avg":    { "type": "half_float" },
              "product_rating_count":  { "type": "integer" },
              "created_at":            { "type": "date" },
              "updated_at":            { "type": "date" },
              "indexed_at":            { "type": "date" },
              "last_event_id":         { "type": "keyword" },
              "version":               { "type": "date" }
            }
          }
        }
        """;

    private static final String CATEGORY_MAPPING_JSON = """
        {
          "settings": {
            "number_of_shards": 1,
            "number_of_replicas": 0,
            "refresh_interval": "1s"
          },
          "mappings": {
            "properties": {
              "category_id":   { "type": "keyword" },
              "name":          { "type": "text", "analyzer": "standard" },
              "slug":          { "type": "keyword" },
              "parent_id":     { "type": "keyword" },
              "level":         { "type": "integer" },
              "is_leaf":       { "type": "boolean" },
              "path":          { "type": "keyword" },
              "product_count": { "type": "long" },
              "is_deleted":    { "type": "boolean" },
              "deleted_at":    { "type": "date" },
              "version":       { "type": "date" }
            }
          }
        }
        """;

    private static final String SUGGESTION_MAPPING_JSON = """
        {
          "settings": {
            "number_of_shards": 1,
            "number_of_replicas": 0,
            "refresh_interval": "1s",
            "analysis": {
              "analyzer": {
                "suggest_analyzer": {
                  "type": "custom",
                  "tokenizer": "standard",
                  "filter": ["lowercase"]
                }
              }
            }
          },
          "mappings": {
            "properties": {
              "suggestion_id":   { "type": "keyword" },
              "text": {
                "type": "text",
                "analyzer": "suggest_analyzer",
                "fields": {
                  "keyword": { "type": "keyword" }
                }
              },
              "text_normalized": { "type": "keyword" },
              "type":            { "type": "keyword" },
              "source":          { "type": "keyword" },
              "scope":           { "type": "keyword" },
              "weight":          { "type": "long" },
              "build_version":   { "type": "long" },
              "updated_at":      { "type": "date" }
            }
          }
        }
        """;

    /**
     * Replaces the production {@code IndexInitializer} with a test-friendly
     * version that creates indices using simplified mappings (no ICU plugin).
     */
    @Bean
    @Primary
    @Order(1)
    public ApplicationRunner testIndexInitializer(
            ElasticsearchClient esClient,
            SearchProperties properties) {
        return (ApplicationArguments args) -> {
            log.info("TestIndexInitializer: Creating Elasticsearch indices with simplified mappings...");

            createIndexIfNotExists(esClient, "products_v1", PRODUCT_MAPPING_JSON);
            ensureAlias(esClient, "products_v1", properties.getEs().getIndexAliases().getProducts(), false);
            ensureAlias(esClient, "products_v1", properties.getEs().getIndexAliases().getProductsWrite(), true);

            createIndexIfNotExists(esClient, "categories_v1", CATEGORY_MAPPING_JSON);
            ensureAlias(esClient, "categories_v1", properties.getEs().getIndexAliases().getCategories(), false);
            ensureAlias(esClient, "categories_v1", properties.getEs().getIndexAliases().getCategoriesWrite(), true);

            createIndexIfNotExists(esClient, "search_suggestions_v1", SUGGESTION_MAPPING_JSON);
            ensureAlias(esClient, "search_suggestions_v1", properties.getEs().getIndexAliases().getSuggestions(), false);

            log.info("TestIndexInitializer: Index initialization completed");
        };
    }

    private void createIndexIfNotExists(ElasticsearchClient esClient, String indexName, String mappingJson) {
        try {
            boolean exists = esClient.indices()
                .exists(ExistsRequest.of(b -> b.index(indexName)))
                .value();
            if (exists) {
                log.info("TestIndexInitializer: Index [{}] already exists, skipping", indexName);
                return;
            }
            esClient.indices().create(CreateIndexRequest.of(b -> b
                .index(indexName)
                .withJson(new StringReader(mappingJson))
            ));
            log.info("TestIndexInitializer: Created index [{}]", indexName);
        } catch (Exception e) {
            log.error("TestIndexInitializer: Failed to create index [{}]", indexName, e);
            throw new RuntimeException("Failed to create index " + indexName, e);
        }
    }

    private void ensureAlias(ElasticsearchClient esClient, String indexName, String aliasName, boolean isWriteIndex) {
        try {
            boolean aliasExists = esClient.indices()
                .existsAlias(b -> b.name(aliasName))
                .value();
            if (aliasExists) {
                log.info("TestIndexInitializer: Alias [{}] already exists, skipping", aliasName);
                return;
            }
            esClient.indices().updateAliases(b -> b
                .actions(a -> a.add(add -> add
                    .index(indexName)
                    .alias(aliasName)
                    .isWriteIndex(isWriteIndex)
                ))
            );
            log.info("TestIndexInitializer: Created alias [{}] -> [{}]", aliasName, indexName);
        } catch (Exception e) {
            log.error("TestIndexInitializer: Failed to create alias [{}]", aliasName, e);
            throw new RuntimeException("Failed to create alias " + aliasName, e);
        }
    }
}
