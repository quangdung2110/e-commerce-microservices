package com.ecommerce.search.domain.event;

import java.util.Optional;

/**
 * The CloudEvents {@code type} allowlist.
 *
 * <p>Search shares its topics with other consumers, so a type outside this list is
 * ignored, acknowledged and counted in {@code search_event_ignored_total} — normal
 * behaviour, not an error ({@code docs/search/interfaces.md §4}).
 */
public enum CatalogEventType {

    SKU_UPSERTED("com.ecommerce.catalog.sku-upserted.v1"),
    SKU_DELETED("com.ecommerce.catalog.sku-deleted.v1"),
    PRODUCT_DELETED("com.ecommerce.catalog.product-deleted.v1"),
    CATEGORY_UPSERTED("com.ecommerce.catalog.category-upserted.v1"),
    CATEGORY_DELETED("com.ecommerce.catalog.category-deleted.v1"),
    CATALOG_REINDEX_REQUESTED("com.ecommerce.catalog.reindex-requested.v1"),
    STOCK_SNAPSHOT("com.ecommerce.inventory.stock-snapshot.v1"),
    REVIEW_METRICS_UPDATED("com.ecommerce.review.metrics-updated.v1");

    private final String typeUri;

    CatalogEventType(String typeUri) {
        this.typeUri = typeUri;
    }

    public String typeUri() {
        return typeUri;
    }

    public static Optional<CatalogEventType> from(String typeUri) {
        if (typeUri == null) {
            return Optional.empty();
        }
        for (CatalogEventType type : values()) {
            if (type.typeUri.equals(typeUri)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
