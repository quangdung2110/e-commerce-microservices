package com.ecommerce.search.common;

public final class ServiceConstants {

    private ServiceConstants() {}

    public static final String SERVICE_NAME = "search-service";

    // Pagination defaults
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_SUGGEST_SIZE = 20;

    // Kafka consumer group
    public static final String CONSUMER_GROUP_SEARCH_INDEXER = "search-product-indexer";

    // Kafka topics
    public static final String TOPIC_PRODUCT_EVENTS = "t.product.events";
    public static final String TOPIC_CATEGORY_EVENTS = "t.category.events";
    public static final String TOPIC_REVIEW_EVENTS = "t.review.events";

    // JSON Schema version
    public static final int SCHEMA_VERSION = 1;

    // JWT claims header
    public static final String CLAIMS_HEADER = "X-User-Claims";

    // Metric names (Micrometer)
    public static final String METRIC_INDEXING_TOTAL = "search.indexing.total";
    public static final String METRIC_ES_ERROR_TOTAL = "search.es.error.total";
    public static final String METRIC_DOCUMENT_MISSING_TOTAL = "search.document.missing.total";
    public static final String METRIC_PAYLOAD_INVALID_TOTAL = "search.payload.invalid.total";
    public static final String METRIC_CATEGORY_ENRICHMENT_MISS_TOTAL = "search.category.enrichment.miss.total";
    public static final String METRIC_EVENT_IGNORED_TOTAL = "search.event.ignored.total";
}
