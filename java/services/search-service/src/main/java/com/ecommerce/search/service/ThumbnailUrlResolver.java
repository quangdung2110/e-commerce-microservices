package com.ecommerce.search.service;

import com.ecommerce.search.config.SearchProperties;
import org.springframework.stereotype.Component;

/**
 * Turns the {@code thumbnail_storage_key} Product already resolved into the
 * {@code thumbnail_url} of the response.
 *
 * <p>Product picks the effective thumbnail (SKU image first, then product image) and
 * publishes it on {@code sku-upserted} and on every export, so event-driven indexing and a
 * full reindex agree. Search must not derive one from {@code media[]}; a {@code null} key
 * means the configured placeholder.
 */
@Component
public class ThumbnailUrlResolver {

    private final String cdnBaseUrl;
    private final String placeholderUrl;

    public ThumbnailUrlResolver(SearchProperties properties) {
        String base = properties.media().cdnBaseUrl();
        this.cdnBaseUrl = base.endsWith("/") ? base : base + "/";
        this.placeholderUrl = properties.media().placeholderUrl();
    }

    public String resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return placeholderUrl;
        }
        String key = storageKey.startsWith("/") ? storageKey.substring(1) : storageKey;
        return cdnBaseUrl + key;
    }
}
