package com.ecommerce.search.repository.es;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * ADR-027: a card nests the SKU documents verbatim, so its {@code skus} mapping must stay exactly
 * the {@code products} mapping and its analysis the same analysers; otherwise a filter or a text
 * match would behave differently on the two paths. Regenerate {@code product_cards.index.json}
 * whenever {@code products.index.json} changes.
 */
class ProductCardSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DOCS = Path.of("..", "..", "..", "docs", "search", "schema");

    @Test
    void nestsTheProductsMappingVerbatimWithTheSameAnalysis() throws IOException {
        JsonNode products = JSON.readTree(resource("search/schema/products.index.json"));
        JsonNode cards = JSON.readTree(resource("search/schema/product_cards.index.json"));

        assertThat(cards.at("/mappings/dynamic").asText()).isEqualTo("strict");
        assertThat(cards.at("/mappings/properties/skus/type").asText()).isEqualTo("nested");
        assertThat(cards.at("/mappings/properties/skus/dynamic").asText()).isEqualTo("strict");
        assertThat(cards.at("/mappings/properties/skus/properties")).isEqualTo(products.at("/mappings/properties"));
        assertThat(cards.at("/settings/analysis")).isEqualTo(products.at("/settings/analysis"));
        assertThat(cards.at("/mappings/properties/product_id/type").asText()).isEqualTo("keyword");
    }

    @Test
    void theClasspathCopyIsTheCanonicalFile() throws IOException {
        assumeThat(Files.isDirectory(DOCS)).as("docs/search/schema is on disk").isTrue();

        assertThat(resource("search/schema/product_cards.index.json"))
                .isEqualTo(Files.readString(DOCS.resolve("product_cards.index.json"), StandardCharsets.UTF_8));
    }

    private static String resource(String path) throws IOException {
        try (var in = ProductCardSchemaTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("classpath resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
