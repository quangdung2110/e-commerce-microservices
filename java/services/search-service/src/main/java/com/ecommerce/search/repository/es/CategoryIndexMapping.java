package com.ecommerce.search.repository.es;

/**
 * Static index mapping for the {@code categories_v1} Elasticsearch index.
 *
 * <p>Defines the index settings and field mappings for the category tree index.
 * The {@code product_count} field is maintained in real-time via
 * {@code _update_by_query} triggered by product lifecycle events.
 *
 * @see <a href="../../../../../../../../docs/search/search_elasticsearch_schema.md#3-categories-index--mapping--document-structure">
 *     Elasticsearch Schema §3 — Categories Index</a>
 */
public final class CategoryIndexMapping {

    private CategoryIndexMapping() {}

    /**
     * Complete index settings and mapping JSON for {@code categories_v1}.
     *
     * <p>Settings include:
     * <ul>
     *   <li>{@code number_of_shards: 1} — small index (hundreds of categories)</li>
     *   <li>{@code number_of_replicas: 1} — 1 replica for HA</li>
     *   <li>{@code refresh_interval: 5s} — lower refresh frequency acceptable</li>
     * </ul>
     */
    public static final String MAPPING_JSON = """
        {
          "settings": {
            "number_of_shards": 1,
            "number_of_replicas": 1,
            "refresh_interval": "5s"
          },
          "mappings": {
            "properties": {
              "category_id":   { "type": "keyword" },
              "name": {
                "type": "text",
                "fields": {
                  "keyword": { "type": "keyword" }
                }
              },
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
}
