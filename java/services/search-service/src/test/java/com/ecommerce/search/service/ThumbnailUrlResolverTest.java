package com.ecommerce.search.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThumbnailUrlResolverTest {

    private static final String PLACEHOLDER = "http://localhost:8088/media/placeholder/product.svg";

    @Test
    @DisplayName("Base có hay không có dấu / cuối đều cho cùng URL")
    void trailingSlashOfTheBaseDoesNotMatter() {
        assertThat(new ThumbnailUrlResolver("http://localhost:8088/media", PLACEHOLDER).resolve("skus/a.jpg"))
                .isEqualTo("http://localhost:8088/media/skus/a.jpg");
        assertThat(new ThumbnailUrlResolver("http://localhost:8088/media/", PLACEHOLDER).resolve("skus/a.jpg"))
                .isEqualTo("http://localhost:8088/media/skus/a.jpg");
    }

    @Test
    @DisplayName("Storage key được encode từng segment như BFF, bỏ dấu / đầu")
    void storageKeyIsEncodedLikeTheBff() {
        ThumbnailUrlResolver resolver = new ThumbnailUrlResolver("https://cdn.example.com/media", PLACEHOLDER);

        assertThat(resolver.resolve("products/áo thun/hero#1.jpg"))
                .isEqualTo("https://cdn.example.com/media/products/%C3%A1o%20thun/hero%231.jpg");
        assertThat(resolver.resolve("/leading/slash.png")).isEqualTo("https://cdn.example.com/media/leading/slash.png");
    }

    @Test
    @DisplayName("Không có thumbnail thì trả placeholder")
    void missingKeyIsThePlaceholder() {
        ThumbnailUrlResolver resolver = new ThumbnailUrlResolver("https://cdn.example.com/media", PLACEHOLDER);

        assertThat(resolver.resolve(null)).isEqualTo(PLACEHOLDER);
        assertThat(resolver.resolve(" ")).isEqualTo(PLACEHOLDER);
    }
}
