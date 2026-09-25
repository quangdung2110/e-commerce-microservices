package com.ecommerce.search.service;

import com.ecommerce.search.config.SearchProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Turns the {@code thumbnail_storage_key} Product already resolved into the
 * {@code thumbnail_url} of the response.
 *
 * <p>Product picks the effective thumbnail (SKU image first, then product image) and
 * publishes it on {@code sku-upserted} and on every export, so event-driven indexing and a
 * full reindex agree. Search must not derive one from {@code media[]}; a {@code null} key
 * means the configured placeholder.
 *
 * <p>The URL is built exactly like the Product Detail BFF builds its media URLs (base,
 * slash, storage key with each segment percent-encoded), and both read the same CDN base
 * and placeholder, so a product card and its detail page never point at different files
 * (docs/product-detail-bff ADR-005).
 */
@Component
public class ThumbnailUrlResolver {

    private final String cdnBaseUrl;
    private final String placeholderUrl;

    @Autowired
    public ThumbnailUrlResolver(SearchProperties properties) {
        this(properties.media().cdnBaseUrl(), properties.media().placeholderUrl());
    }

    ThumbnailUrlResolver(String cdnBaseUrl, String placeholderUrl) {
        this.cdnBaseUrl = cdnBaseUrl.endsWith("/") ? cdnBaseUrl : cdnBaseUrl + "/";
        this.placeholderUrl = placeholderUrl;
    }

    public String resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return placeholderUrl;
        }
        String key = storageKey.replaceFirst("^/+", "");
        return cdnBaseUrl + Arrays.stream(key.split("/", -1))
                .map(segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
    }
}
