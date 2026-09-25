package com.ecommerce.pdp.compose;

import com.ecommerce.pdp.config.PdpProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Dựng URL công khai từ storage key Product trả (ADR-005, PDP-BR-011). Product không phát
 * CDN URL (Product ADR-013), còn storage key là dữ liệu {@code INTERNAL}: nó không bao giờ
 * ra khỏi BFF.
 */
@Component
public class MediaUrls {

    private final String cdnBaseUrl;
    private final String placeholderUrl;

    public MediaUrls(PdpProperties properties) {
        this.cdnBaseUrl = properties.media().cdnBaseUrl();
        this.placeholderUrl = properties.media().placeholderUrl();
    }

    /** CDN base + storage key, percent-encode từng segment để key lạ không phá URL. */
    public String url(String storageKey) {
        String key = storageKey.replaceFirst("^/+", "");
        String encoded = Arrays.stream(key.split("/", -1))
                .map(segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
        return cdnBaseUrl + "/" + encoded;
    }

    /** SKU không có ảnh nhận URL placeholder cấu hình, cùng quy ước với Search. */
    public String thumbnail(String storageKey) {
        return storageKey == null ? placeholderUrl : url(storageKey);
    }
}
