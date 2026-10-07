package com.ecommerce.checkout.media;

import com.ecommerce.checkout.config.CheckoutProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Builds the public image URL from the Product storage key (interfaces.md, {@code items[].image_url}).
 * Product does not emit CDN URLs; the key is internal data that never leaves Checkout. The CDN base
 * and the placeholder must equal Search's and the Product Detail BFF's so a SKU looks the same
 * everywhere.
 */
@Component
public class MediaUrls {

    private final String cdnBaseUrl;
    private final String placeholderUrl;

    public MediaUrls(CheckoutProperties properties) {
        this.cdnBaseUrl = properties.media().cdnBaseUrl();
        this.placeholderUrl = properties.media().placeholderUrl();
    }

    /** CDN base + storage key, percent-encoding every path segment so an odd key cannot break the URL. */
    public String url(String storageKey) {
        String key = storageKey.replaceFirst("^/+", "");
        String encoded = Arrays.stream(key.split("/", -1))
                .map(segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
        return cdnBaseUrl + "/" + encoded;
    }

    /** What the quotation shows: the CDN URL, or the configured placeholder when the SKU has no image. */
    public String displayUrl(String storageKey) {
        return hasImage(storageKey) ? url(storageKey) : placeholderUrl;
    }

    /** What Order stores: the CDN URL, or {@code null}; a placeholder is never persisted on an order. */
    public String orderUrl(String storageKey) {
        return hasImage(storageKey) ? url(storageKey) : null;
    }

    private static boolean hasImage(String storageKey) {
        return storageKey != null && !storageKey.isBlank();
    }
}
