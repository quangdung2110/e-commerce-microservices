package com.platform.productservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Allowlist trong {@code application.yml} phải bind được từng khóa. Mặc định trong
 * {@link ProductProperties} trùng giá trị, nên nếu chỉ kiểm object đã bind thì một khóa gõ sai
 * trong YAML vẫn qua test; ở đây kiểm riêng từng khóa có mặt.
 */
class ProductPropertiesBindingTest {

    @Test
    @DisplayName("application.yml khai báo đủ ba allowlist theo nhóm route")
    void applicationYamlDeclaresEveryAllowlist() throws IOException {
        Binder binder = binderOf("application.yml");

        assertThat(listAt(binder, "product.internal.reindex-clients"))
                .containsExactly("search-service");
        assertThat(listAt(binder, "product.internal.snapshot-clients"))
                .containsExactly("search-service", "promotion-service");
        assertThat(listAt(binder, "product.internal.buyer-read-clients"))
                .containsExactly("product-detail-bff");
    }

    @Test
    @DisplayName("Promotion không nằm trong allowlist export")
    void promotionIsNotAnExportClient() throws IOException {
        ProductProperties properties = binderOf("application.yml")
                .bind("product", ProductProperties.class).get();

        assertThat(properties.getInternal().getReindexClients()).doesNotContain("promotion-service");
        assertThat(properties.getInternal().getSnapshotClients()).contains("promotion-service");
    }

    private static List<String> listAt(Binder binder, String key) {
        return binder.bind(key, Bindable.listOf(String.class))
                .orElseThrow(() -> new AssertionError(key + " is not declared in application.yml"));
    }

    private static Binder binderOf(String resource) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load(resource, new ClassPathResource(resource));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        return new Binder(ConfigurationPropertySources.from(propertySources));
    }
}
