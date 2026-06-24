package com.ecommerce.search.repository.es;

/**
 * Static index mapping for the {@code search_suggestions_v1} Elasticsearch index.
 *
 * <p>This index serves the autocomplete/search-as-you-type API. It uses edge_ngram
 * tokenization for fast prefix matching and is rebuilt atomically on a daily cadence.
 *
 * <p>Because the index is fully rebuilt each cycle (no partial updates), no
 * {@code version} field is needed — ordering is guaranteed by the atomic alias-swap.
 *
 * @see <a href="../../../../../../../../docs/search/search_elasticsearch_schema.md#4-search-suggestions-index-mapping--ranking-contract">
 *     Elasticsearch Schema §4 — Search Suggestions Index</a>
 */
public final class SuggestionIndexMapping {

    private SuggestionIndexMapping() {}

    /**
     * Complete index settings and mapping JSON for {@code search_suggestions_v1}.
     *
     * <p>Settings include:
     * <ul>
     *   <li>{@code number_of_shards: 1} — small index suitable for daily rebuild</li>
     *   <li>{@code number_of_replicas: 1} — 1 replica for HA</li>
     *   <li>{@code refresh_interval: 5s} — lower refresh for batch-ingest friendly</li>
     *   <li>Edge n-gram tokenizer (min=2, max=20) for prefix matching</li>
     * </ul>
     */
    public static final String MAPPING_JSON = """
        {
          "settings": {
            "number_of_shards": 1,
            "number_of_replicas": 1,
            "refresh_interval": "5s",
            "analysis": {
              "analyzer": {
                "suggest_index_analyzer": {
                  "type": "custom",
                  "tokenizer": "suggest_edge_ngram_tokenizer",
                  "filter": ["icu_folding", "lowercase"]
                },
                "suggest_search_analyzer": {
                  "type": "custom",
                  "tokenizer": "icu_tokenizer",
                  "filter": ["icu_folding", "lowercase"]
                }
              },
              "tokenizer": {
                "suggest_edge_ngram_tokenizer": {
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
              "suggestion_id":       { "type": "keyword" },
              "text": {
                "type": "text",
                "analyzer": "suggest_index_analyzer",
                "search_analyzer": "suggest_search_analyzer",
                "fields": {
                  "keyword": { "type": "keyword" }
                }
              },
              "text_normalized":     { "type": "keyword" },
              "type":                { "type": "keyword" },
              "source":              { "type": "keyword" },
              "scope":               { "type": "keyword" },
              "category_id":         { "type": "keyword" },
              "product_id":          { "type": "keyword" },
              "weight":              { "type": "long" },
              "product_rating_avg":  { "type": "half_float" },
              "product_rating_count": { "type": "integer" },
              "product_count":       { "type": "long" },
              "build_version":       { "type": "long" },
              "updated_at":          { "type": "date" }
            }
          }
        }
        """;
}
