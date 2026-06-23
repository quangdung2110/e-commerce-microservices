# Module 03: Elasticsearch Configuration & Repositories

> **Status:** Module 3 of 14 - **NOT YET IMPLEMENTED**
> **Prerequisites:** Module 02 (Domain Model)
> **Dependents:** Modules 04, 05, 08, 09, 10, 11, 12
> **Estimated Effort:** 1 person-day

> ## Implementation Note
> Chưa implement. Plan chi tiết bên dưới. Khi implement, đánh dấu `[x]` cho từng task.

---

## 1. Overview

This module configures the Elasticsearch client (Java API Client 8.x) và provides the base repository operations:
- **ElasticsearchClientConfig:** ES client bean + Jackson ObjectMapper config
- **IndexInitializer:** Tạo indices với mapping nếu chưa tồn tại (idempotent, chạy on startup)
- **UpdateByQueryExecutor:** Wrapper cho `_update_by_query` operation với throttle + retry
- **ProductSearchRepository:** Base read operations cho `products` alias
- **SuggestRepository:** Base read operations cho `search_suggestions` alias
- **CategoryRepository:** Base read operations cho `categories` alias

---

## 2. Elasticsearch Client Configuration

### 2.1 File: `repository/es/ElasticsearchClientConfig.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/repository/es/ElasticsearchClientConfig.java`

- [ ] Create `@Configuration` class
- [ ] Create `ElasticsearchClient` bean từ `ElasticsearchClientProvider` (cấu hình URI, username, password, timeouts)
- [ ] Create `ElasticsearchTransport` bean (sử dụng `RestClient` underneath)
- [ ] Create `RestClient` bean với timeouts (connect 5s, socket 30s)
- [ ] Configure Jackson ObjectMapper cho ES client (ISO 8601 dates, snake_case)
- [ ] Tạo `IndexName` provider bean để expose các aliases từ `SearchProperties`

```java
package com.ecommerce.search.repository.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;

@Slf4j
@Configuration
public class ElasticsearchClientConfig {

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String esUri;

    @Value("${spring.elasticsearch.username:}")
    private String esUsername;

    @Value("${spring.elasticsearch.password:}")
    private String esPassword;

    @Value("${spring.elasticsearch.connection-timeout:5s}")
    private Duration connectionTimeout;

    @Value("${spring.elasticsearch.socket-timeout:30s}")
    private Duration socketTimeout;

    @Bean
    public ObjectMapper esObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    @Bean
    public RestClient restClient() {
        URI uri = URI.create(esUri);
        RestClientBuilder builder = RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()))
            .setRequestConfigCallback(rc -> rc
                .setConnectTimeout((int) connectionTimeout.toMillis())
                .setSocketTimeout((int) socketTimeout.toMillis()));

        if (esUsername != null && !esUsername.isEmpty()) {
            CredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(esUsername, esPassword));
            builder.setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credsProvider));
        }

        return builder.build();
    }

    @Bean
    public ElasticsearchTransport elasticsearchTransport(RestClient restClient, ObjectMapper esObjectMapper) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper(esObjectMapper));
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
```

> **Note:** Sử dụng `RestClient` underlying (low-level) + `ElasticsearchTransport` + `ElasticsearchClient` (high-level Java API Client 8.x). Sử dụng custom `ObjectMapper` để control JSON serialization (snake_case, ISO 8601 dates).

---

## 3. Index Initializer

### 3.1 File: `repository/es/IndexInitializer.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/repository/es/IndexInitializer.java`

- [ ] Create `@Component` implements `ApplicationRunner` (chạy on startup)
- [ ] Inject `ElasticsearchClient` + `SearchProperties`
- [ ] Tạo `products_v1` index nếu chưa tồn tại (apply mapping + settings)
- [ ] Tạo `categories_v1` index nếu chưa tồn tại
- [ ] Tạo `search_suggestions_v1` index nếu chưa tồn tại
- [ ] Tạo aliases (`products` + `products_write`, `categories` + `categories_write`, `search_suggestions`)
- [ ] Log kết quả (created / already exists / failed)
- [ ] **Idempotent:** Chạy nhiều lần không gây lỗi
- [ ] **Production safety:** Không drop existing indices (chỉ tạo mới nếu chưa có)

```java
package com.ecommerce.search.repository.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.ecommerce.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.List;

@Slf4j
@Component
@Order(1)  // chạy sớm nhất
@RequiredArgsConstructor
public class IndexInitializer implements ApplicationRunner {

    private final ElasticsearchClient esClient;
    private final SearchProperties properties;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        createIndexIfNotExists("products_v1", ProductIndexMapping.MAPPING_JSON);
        ensureAlias("products_v1", properties.getEs().getIndexAliases().getProducts(), false);
        ensureAlias("products_v1", properties.getEs().getIndexAliases().getProductsWrite(), true);

        createIndexIfNotExists("categories_v1", CategoryIndexMapping.MAPPING_JSON);
        ensureAlias("categories_v1", properties.getEs().getIndexAliases().getCategories(), false);
        ensureAlias("categories_v1", properties.getEs().getIndexAliases().getCategoriesWrite(), true);

        createIndexIfNotExists("search_suggestions_v1", SuggestionIndexMapping.MAPPING_JSON);
        ensureAlias("search_suggestions_v1", properties.getEs().getIndexAliases().getSuggestions(), false);
    }

    private void createIndexIfNotExists(String indexName, String mappingJson) {
        try {
            boolean exists = esClient.indices().exists(ExistsRequest.of(b -> b.index(indexName))).value();
            if (exists) {
                log.info("Index {} already exists, skipping creation", indexName);
                return;
            }
            esClient.indices().create(CreateIndexRequest.of(b -> b
                .index(indexName)
                .withJson(new StringReader(mappingJson))
            ));
            log.info("Created index {}", indexName);
        } catch (Exception e) {
            log.error("Failed to create index {}", indexName, e);
            throw new RuntimeException("Failed to create index " + indexName, e);
        }
    }

    private void ensureAlias(String indexName, String aliasName, boolean isWrite) {
        try {
            boolean aliasExists = esClient.indices().existsAlias(b -> b.name(aliasName)).value();
            if (aliasExists) {
                log.info("Alias {} already exists, skipping", aliasName);
                return;
            }
            esClient.indices().updateAliases(b -> b
                .actions(a -> a.add(add -> add.index(indexName).alias(aliasName).isWriteIndex(isWrite)))
            );
            log.info("Created alias {} -> {} (isWrite={})", aliasName, indexName, isWrite);
        } catch (Exception e) {
            log.error("Failed to create alias {}", aliasName, e);
            throw new RuntimeException("Failed to create alias " + aliasName, e);
        }
    }
}
```

### 3.2 File: `repository/es/ProductIndexMapping.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/repository/es/ProductIndexMapping.java`

- [ ] Create constants class với `MAPPING_JSON` (theo [Elasticsearch Schema §2.1, §2.2](../search_elasticsearch_schema.md))
- [ ] Inline JSON string với index settings + mappings

```java
package com.ecommerce.search.repository.es;

public final class ProductIndexMapping {
    private ProductIndexMapping() {}

    public static final String MAPPING_JSON = """
        {
          "settings": {
            "number_of_shards": 3,
            "number_of_replicas": 1,
            "refresh_interval": "1s",
            "analysis": {
              "analyzer": {
                "vietnamese_analyzer": {
                  "type": "custom",
                  "tokenizer": "icu_tokenizer",
                  "filter": ["icu_folding", "lowercase", "vi_stop"]
                },
                "autocomplete_analyzer": {
                  "type": "custom",
                  "tokenizer": "autocomplete_tokenizer",
                  "filter": ["icu_folding", "lowercase"]
                },
                "autocomplete_search_analyzer": {
                  "type": "custom",
                  "tokenizer": "icu_tokenizer",
                  "filter": ["icu_folding", "lowercase"]
                }
              },
              "tokenizer": {
                "autocomplete_tokenizer": {
                  "type": "edge_ngram",
                  "min_gram": 2,
                  "max_gram": 20,
                  "token_chars": ["letter", "digit"]
                }
              },
              "filter": {
                "vi_stop": {
                  "type": "stop",
                  "stopwords": ["và", "của", "là", "cho", "với", "trong", "này", "có", "được", "các"]
                }
              }
            }
          },
          "mappings": {
            "properties": {
              "sku_id":              { "type": "keyword" },
              "product_id":          { "type": "keyword" },
              "category_id":         { "type": "keyword" },
              "category_name":       { "type": "keyword" },
              "category_path":       { "type": "keyword" },
              "name": {
                "type": "text",
                "analyzer": "vietnamese_analyzer",
                "fields": {
                  "autocomplete": { "type": "text", "analyzer": "autocomplete_analyzer", "search_analyzer": "autocomplete_search_analyzer" },
                  "keyword": { "type": "keyword" }
                }
              },
              "description":         { "type": "text", "analyzer": "vietnamese_analyzer", "index_options": "offsets" },
              "thumbnail_url":       { "type": "keyword" },
              "product_status":      { "type": "keyword" },
              "sku_status":          { "type": "keyword" },
              "searchable_attributes": { "type": "keyword" },
              "variant_attributes":    { "type": "keyword" },
              "original_price":      { "type": "scaled_float", "scaling_factor": 100 },
              "promotional_price":   { "type": "scaled_float", "scaling_factor": 100 },
              "best_price":          { "type": "scaled_float", "scaling_factor": 100 },
              "is_in_stock":         { "type": "boolean" },
              "has_promotion":       { "type": "boolean" },
              "is_deleted":          { "type": "boolean" },
              "deleted_at":          { "type": "date" },
              "product_rating_avg":  { "type": "half_float" },
              "product_rating_count": { "type": "integer" },
              "created_at":          { "type": "date" },
              "updated_at":          { "type": "date" },
              "indexed_at":          { "type": "date" },
              "last_event_id":       { "type": "keyword" },
              "version":             { "type": "date" }
            }
          }
        }
        """;
}
```

### 3.3 File: `repository/es/CategoryIndexMapping.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/repository/es/CategoryIndexMapping.java`

- [ ] Define `MAPPING_JSON` cho `categories_v1` (theo [Elasticsearch Schema §3.1, §3.2](../search_elasticsearch_schema.md))

### 3.4 File: `repository/es/SuggestionIndexMapping.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/repository/es/SuggestionIndexMapping.java`

- [ ] Define `MAPPING_JSON` cho `search_suggestions_v1` (theo [Elasticsearch Schema §4.1, §4.2](../search_elasticsearch_schema.md))

---

## 4. UpdateByQuery Executor (Wrapper)

### 4.1 File: `repository/es/UpdateByQueryExecutor.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/repository/es/UpdateByQueryExecutor.java`

- [ ] Create `@Component` class
- [ ] Inject `ElasticsearchClient` + `SearchProperties`
- [ ] Provide method: `<T> UpdateByQueryResponse updateByQuery(String alias, Query query, String scriptSource, Map<String, Object> params)`
- [ ] Apply throttle `requests_per_second=300` + `conflicts=proceed` (config từ `properties.es.updateByQueryThrottleRps`)
- [ ] Retry transient errors với exponential backoff (max 3 lần)
- [ ] Log kết quả (updated, total, failures)

```java
package com.ecommerce.search.repository.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import com.ecommerce.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateByQueryExecutor {

    private final ElasticsearchClient esClient;
    private final SearchProperties properties;

    @Retryable(
        retryFor = {Exception.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public UpdateByQueryResponse updateByQuery(String alias, Query query, String scriptSource, Map<String, Object> params) {
        log.debug("Executing _update_by_query on alias={}, query={}", alias, query);

        UpdateByQueryResponse response = esClient.updateByQuery(u -> u
            .index(alias)
            .query(query)
            .script(s -> s.inline(i -> i.source(scriptSource).params(java.util.Map.of("params", params))))
            .refresh(true)
            .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
            .requestsPerSecond((float) properties.getEs().getUpdateByQueryThrottleRps())
        );

        log.info("_update_by_query on alias={}: updated={}, total={}, failures={}",
            alias, response.updated(), response.total(), response.failures().size());

        return response;
    }
}
```

> **Note:** Cần thêm `spring-retry` dependency vào `pom.xml`. Sử dụng `@Retryable` để retry transient errors (timeout, 429, 503).

---

## 5. Base Search Repository

### 5.1 File: `repository/ProductSearchRepository.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/repository/ProductSearchRepository.java`

- [ ] Create `@Repository` class
- [ ] Inject `ElasticsearchClient` + `SearchProperties`
- [ ] Provide method: `SearchResponse<ProductDocument> search(SearchRequest request)`
- [ ] Provide method: `GetResponse<ProductDocument> getBySkuId(String skuId)`
- [ ] Provide method: `void index(ProductDocument doc)` (write to `products_write` alias)
- [ ] Provide method: `void updateByScript(String skuId, String scriptSource, Map<String, Object> params)` (cho `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `SKU_UPDATED` events)
- [ ] Provide method: `void deleteBySkuId(String skuId)` (cho `PRODUCT_DELETED` tombstone)
- [ ] Provide method: `void bulkIndex(List<ProductDocument> docs)` (cho batch indexing)
- [ ] **Throws** `SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, ...)` on transient errors
- [ ] **Throws** `SearchException(ErrorCode.SEARCH_TIMEOUT, ...)` on timeout

```java
package com.ecommerce.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.SearchResponse;
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

@Slf4j
@Repository
@RequiredArgsConstructor
public class ProductSearchRepository {

    private final ElasticsearchClient esClient;
    private final SearchProperties properties;

    public SearchResponse<ProductDocument> search(SearchRequest request) {
        try {
            return esClient.search(request, ProductDocument.class);
        } catch (IOException | RuntimeException e) {
            log.error("Failed to search products", e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES search failed", e);
        }
    }

    public ProductDocument getBySkuId(String skuId) {
        try {
            GetResponse<ProductDocument> response = esClient.get(g -> g
                .index(properties.getEs().getIndexAliases().getProducts())
                .id(skuId)
            , ProductDocument.class);
            return response.found() ? response.source() : null;
        } catch (IOException | RuntimeException e) {
            log.error("Failed to get product by skuId={}", skuId, e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES get failed", e);
        }
    }

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
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES index failed", e);
        }
    }

    public void updateByScript(String skuId, String scriptSource, Map<String, Object> params) {
        try {
            UpdateResponse<ProductDocument> response = esClient.update(u -> u
                .index(properties.getEs().getIndexAliases().getProductsWrite())
                .id(skuId)
                .script(s -> s.inline(i -> i.source(scriptSource).params(Map.of("params", params))))
                .retryOnConflict(3)
            , ProductDocument.class);
            log.debug("Updated SKU {}: result={}", skuId, response.result());
        } catch (IOException | RuntimeException e) {
            // 404 document_missing_exception: log + metric + skip (do caller handle)
            if (e.getMessage() != null && e.getMessage().contains("document_missing_exception")) {
                log.warn("Document missing for SKU {}: {}", skuId, e.getMessage());
                throw new SearchException(ErrorCode.INTERNAL_ERROR, "Document missing", e);
            }
            log.error("Failed to update SKU {}", skuId, e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES update failed", e);
        }
    }

    public void bulkIndex(List<ProductDocument> docs) {
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
                log.warn("Bulk index completed with errors: total={}, failed={}",
                    response.items().size(), response.items().stream().filter(i -> i.error() != null).count());
            }
        } catch (IOException | RuntimeException e) {
            log.error("Failed to bulk index {} documents", docs.size(), e);
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE, "ES bulk failed", e);
        }
    }
}
```

> **Quan trọng:** Repository chỉ wrap ES exceptions thành `SearchException`. Worker (Module 08) sẽ xử lý `document_missing_exception` riêng.

### 5.2 File: `repository/CategoryRepository.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/repository/CategoryRepository.java`

- [ ] Create `@Repository` class
- [ ] Methods:
  - `CategoryDocument getById(String categoryId)` — lookup for enrichment
  - `void index(CategoryDocument doc)` — write to `categories_write` alias
  - `void updateByScript(String categoryId, String scriptSource, Map<String, Object> params)` — for product_count update

### 5.3 File: `repository/SuggestRepository.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/repository/SuggestRepository.java`

- [ ] Create `@Repository` class
- [ ] Methods:
  - `void bulkIndex(List<SuggestionDocument> docs)` — cho Suggestions Rebuilder
  - `SearchResponse<SuggestionDocument> search(SearchRequest request)` — cho Suggest API

### 5.4 File: `repository/ReindexRepository.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/repository/ReindexRepository.java`

- [ ] Create `@Repository` class (cho Full Reindex Job)
- [ ] Methods:
  - `void createIndex(String newIndexName, String mappingJson)` — tạo index versioned mới
  - `void bulkIndexToVersioned(String indexName, List<ProductDocument> docs)` — ghi vào index versioned (không qua alias)
  - `void swapAlias(String oldIndexName, String newIndexName, String aliasName, String writeAliasName)` — atomic alias swap
  - `void deleteIndex(String indexName)` — cleanup old index sau 24h
  - `void cloneIndex(String sourceIndex, String destIndex)` — cho Phase 1 (Build)
  - `long countDocs(String indexName)` — cho Phase 3 doc count check
  - `long compareDocCount(String indexA, String indexB)` — cho Phase 3 sai số check

---

## 6. Acceptance Criteria

- [ ] `ElasticsearchClient` bean successfully connects tới ES cluster
- [ ] `IndexInitializer` chạy on startup, tạo 3 indices + aliases (idempotent)
- [ ] `UpdateByQueryExecutor` retry đúng với exponential backoff
- [ ] `ProductSearchRepository` operations throw `SearchException` với đúng `ErrorCode`
- [ ] `document_missing_exception` được handle riêng (throw `SearchException(INTERNAL_ERROR, "Document missing")` để worker xử lý)
- [ ] Bulk index chạy đúng với N documents
- [ ] `ReindexRepository.swapAlias` atomic operation thành công

---

## 7. Implementation File Index

| # | File | Status |
|---|------|--------|
| 1 | `repository/es/ElasticsearchClientConfig.java` | ⏳ Not yet |
| 2 | `repository/es/IndexInitializer.java` | ⏳ Not yet |
| 3 | `repository/es/ProductIndexMapping.java` | ⏳ Not yet |
| 4 | `repository/es/CategoryIndexMapping.java` | ⏳ Not yet |
| 5 | `repository/es/SuggestionIndexMapping.java` | ⏳ Not yet |
| 6 | `repository/es/UpdateByQueryExecutor.java` | ⏳ Not yet |
| 7 | `repository/ProductSearchRepository.java` | ⏳ Not yet |
| 8 | `repository/CategoryRepository.java` | ⏳ Not yet |
| 9 | `repository/SuggestRepository.java` | ⏳ Not yet |
| 10 | `repository/ReindexRepository.java` | ⏳ Not yet |
| 11 | `ElasticsearchClientConfigTest.java` | ⏳ Not yet |
| 12 | `IndexInitializerTest.java` | ⏳ Not yet |
| 13 | `ProductSearchRepositoryTest.java` | ⏳ Not yet |
