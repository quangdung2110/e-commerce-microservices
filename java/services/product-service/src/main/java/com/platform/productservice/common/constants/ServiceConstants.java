package com.platform.productservice.common.constants;

public final class ServiceConstants {

    private ServiceConstants() {}

    // Service name
    public static final String SERVICE_NAME = "product-service";

    // Pagination
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    // Redis key prefixes
    public static final String KEY_PREFIX = "product";
    public static final String SKU_KEY_PREFIX = "product:sku";
    public static final String MARKDOWN_KEY_PREFIX = "product:markdown:active";
    public static final String INVENTORY_PENDING_KEY = "product:inventory:pending";

    // Kafka topic names
    public static final String TOPIC_PRODUCT_EVENTS = "t.product.events";
    public static final String TOPIC_CATEGORY_EVENTS = "t.category.events";
    public static final String TOPIC_MARKDOWN_EVENTS = "t.promotion.markdown.events";
    public static final String TOPIC_INVENTORY_EVENTS = "t.inventory.events";

    // Consumer group IDs
    public static final String CG_EVENT_HANDLER = "product-event-handler-group";
    public static final String CG_MARKDOWN = "product-markdown-group";
    public static final String CG_INVENTORY_SYNC = "product-inventory-fast-sync-group";

    // Lock timeout
    public static final int LOCK_TIMEOUT_SECONDS = 5;
    public static final int DISTRIBUTED_LOCK_TTL_SECONDS = 30;

    // Cache TTLs
    public static final int SKU_CACHE_TTL_SECONDS = 3600;
    public static final int MARKDOWN_CACHE_TTL_BUFFER_SECONDS = 300;

    // JSON Schema version
    public static final int SCHEMA_VERSION = 1;
}
