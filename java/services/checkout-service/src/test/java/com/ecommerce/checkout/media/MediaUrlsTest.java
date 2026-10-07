package com.ecommerce.checkout.media;

import com.ecommerce.checkout.config.CheckoutProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MediaUrlsTest {

    private final MediaUrls media = new MediaUrls(new CheckoutProperties("redis://x", null, null, null, null, null,
            null, null, new CheckoutProperties.Media("https://cdn.test/media", "https://cdn.test/placeholder.png", true),
            null, false, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO));

    @Test
    @DisplayName("Every path segment of the storage key is percent-encoded, slashes are kept")
    void encodesSegments() {
        assertThat(media.url("products/ao thun/đen #1?.jpg"))
                .isEqualTo("https://cdn.test/media/products/ao%20thun/%C4%91en%20%231%3F.jpg");
        assertThat(media.url("/leading/slash.jpg")).isEqualTo("https://cdn.test/media/leading/slash.jpg");
    }

    @Test
    @DisplayName("No image: the quotation shows the placeholder, Order receives null")
    void placeholderIsNeverPersisted() {
        assertThat(media.displayUrl("")).isEqualTo("https://cdn.test/placeholder.png");
        assertThat(media.displayUrl(null)).isEqualTo("https://cdn.test/placeholder.png");
        assertThat(media.orderUrl("")).isNull();
        assertThat(media.orderUrl(null)).isNull();
        assertThat(media.orderUrl("a/b.jpg")).isEqualTo("https://cdn.test/media/a/b.jpg");
        assertThat(media.displayUrl("a/b.jpg")).isEqualTo(media.orderUrl("a/b.jpg"));
    }
}
