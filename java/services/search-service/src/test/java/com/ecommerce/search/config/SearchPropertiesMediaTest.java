package com.ecommerce.search.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CDN base URL is the one value Search, the Product Detail BFF, Cart and Checkout must
 * share; like those services, Search refuses to start with one that would build a different
 * URL (ADR-022).
 */
class SearchPropertiesMediaTest {

    @Test
    void anAbsoluteHttpsUrlWithoutATrailingSlashIsAccepted() {
        assertThat(new SearchProperties.Media("https://cdn.example.com/media", "/p.png").cdnBaseUrl())
                .isEqualTo("https://cdn.example.com/media");
    }

    @Test
    void localHttpIsAccepted() {
        assertThat(new SearchProperties.Media("http://localhost:8088/media", "/p.png").cdnBaseUrl())
                .isEqualTo("http://localhost:8088/media");
    }

    @Test
    void aTrailingSlashIsRefused() {
        assertThatThrownBy(() -> new SearchProperties.Media("https://cdn.example.com/media/", "/p.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trailing slash");
    }

    @Test
    void aRelativeOrBlankOrNonHttpUrlIsRefused() {
        for (String bad : new String[]{"/media", "", "  ", "cdn.example.com/media", "ftp://cdn.example.com", "https:///x"}) {
            assertThatThrownBy(() -> new SearchProperties.Media(bad, "/p.png"))
                    .as("'%s' must be refused", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theDefaultComesFromTheDeclarationAndHasNoTrailingSlash() {
        SearchProperties.Media media = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("search.media", SearchProperties.Media.class);

        assertThat(media.cdnBaseUrl()).isEqualTo("https://cdn.local");
    }

    @Test
    void anInvalidConfiguredValueStopsTheBinding() {
        Binder binder = new Binder(new MapConfigurationPropertySource(
                Map.of("search.media.cdn-base-url", "https://cdn.example.com/")));

        assertThatThrownBy(() -> binder.bindOrCreate("search.media", SearchProperties.Media.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
