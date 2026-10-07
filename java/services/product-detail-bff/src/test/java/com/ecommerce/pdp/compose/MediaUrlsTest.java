package com.ecommerce.pdp.compose;

import com.ecommerce.pdp.config.PdpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaUrlsTest {

    private final MediaUrls urls = new MediaUrls(new PdpProperties(null, null, null, null, null, null, null,
            new PdpProperties.Media("https://cdn.example.com/media", "https://cdn.example.com/placeholder.png", true),
            "no-store"));

    @Test
    @DisplayName("Storage key được percent-encode từng segment, giữ dấu gạch chéo")
    void storageKeySegmentsAreEncoded() {
        assertThat(urls.url("products/áo thun/hero#1.jpg"))
                .isEqualTo("https://cdn.example.com/media/products/%C3%A1o%20thun/hero%231.jpg");
        assertThat(urls.url("/leading/slash.png")).isEqualTo("https://cdn.example.com/media/leading/slash.png");
    }

    @Test
    @DisplayName("Thumbnail null thành placeholder")
    void missingThumbnailBecomesPlaceholder() {
        assertThat(urls.thumbnail(null)).isEqualTo("https://cdn.example.com/placeholder.png");
        assertThat(urls.thumbnail("skus/a.jpg")).isEqualTo("https://cdn.example.com/media/skus/a.jpg");
    }
}
