package com.platform.productservice.infrastructure.event;

/**
 * Hằng số CloudEvents theo contracts/events/product/v1/asyncapi.yaml.
 * Đổi giá trị ở đây là breaking change contract.
 */
public final class CatalogEventTypes {

    public static final String SOURCE = "/services/product";
    public static final String SPEC_VERSION = "1.0";
    public static final String DATA_CONTENT_TYPE = "application/json";

    public static final String SKU_UPSERTED = "com.ecommerce.catalog.sku-upserted.v1";
    public static final String SKU_DELETED = "com.ecommerce.catalog.sku-deleted.v1";
    public static final String PRODUCT_DELETED = "com.ecommerce.catalog.product-deleted.v1";
    public static final String CATEGORY_UPSERTED = "com.ecommerce.catalog.category-upserted.v1";
    public static final String CATEGORY_DELETED = "com.ecommerce.catalog.category-deleted.v1";
    public static final String CATALOG_REINDEX_REQUESTED = "com.ecommerce.catalog.reindex-requested.v1";

    public static final String MARKDOWN_SNAPSHOT = "com.ecommerce.promotion.product-markdown-snapshot.v1";

    private static final String SCHEMA_BASE =
            "https://contracts.ecommerce.local/events/product/v1/schemas/";

    private CatalogEventTypes() {
    }

    /** dataschema của CloudEvent; trùng $id của JSON Schema tương ứng. */
    public static String dataSchema(String eventType) {
        return SCHEMA_BASE + switch (eventType) {
            case SKU_UPSERTED -> "sku-upserted";
            case SKU_DELETED -> "sku-deleted";
            case PRODUCT_DELETED -> "product-deleted";
            case CATEGORY_UPSERTED -> "category-upserted";
            case CATEGORY_DELETED -> "category-deleted";
            case CATALOG_REINDEX_REQUESTED -> "catalog-reindex-requested";
            default -> throw new IllegalArgumentException("Unknown catalog event type: " + eventType);
        } + ".schema.json";
    }
}
