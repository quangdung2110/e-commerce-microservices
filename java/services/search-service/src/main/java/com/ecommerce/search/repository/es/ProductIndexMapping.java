package com.ecommerce.search.repository.es;

/**
 * Static index mapping for the {@code products_v1} Elasticsearch index.
 *
 * <p>Defines the index settings (analysis, shards, replicas) and field mappings
 * for the SKU-first product index. The mapping includes:
 * <ul>
 *   <li>Vietnamese-aware text analysis via ICU tokenizer</li>
 *   <li>Autocomplete sub-field with edge_ngram tokenizer</li>
 *   <li>Vietnamese stop words filter</li>
 *   <li>Scaled floats for price fields (scaling_factor=100)</li>
 *   <li>Version field for guard ordering</li>
 * </ul>
 *
 * @see <a href="../../../../../../../../docs/search/search_elasticsearch_schema.md#2-products-index--mapping--document-structure">
 *     Elasticsearch Schema §2 — Products Index</a>
 */
public final class ProductIndexMapping {

    private ProductIndexMapping() {}

    /**
     * Complete index settings and mapping JSON for {@code products_v1}.
     *
     * <p>Settings include:
     * <ul>
     *   <li>{@code number_of_shards: 3} — 3 shards for parallel query</li>
     *   <li>{@code number_of_replicas: 1} — 1 replica for HA</li>
     *   <li>{@code refresh_interval: 1s} — near-realtime visibility</li>
     *   <li>Vietnamese text analysis pipeline</li>
     *   <li>Autocomplete edge_ngram tokenizer (min=2, max=20)</li>
     * </ul>
     */
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
                  "autocomplete": {
                    "type": "text",
                    "analyzer": "autocomplete_analyzer",
                    "search_analyzer": "autocomplete_search_analyzer"
                  },
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
