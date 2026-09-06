package com.ecommerce.search.repository.es;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * {@code docs/search/schema/scripts/README.md} requires the painless scripts to be loaded
 * verbatim and forbids a caller from editing its own copy. The mappings carry the same
 * rule: they are the source of truth for the index lifecycle.
 *
 * <p>This test is what makes that enforceable — the classpath resources must stay
 * byte-identical to the published files. It is skipped when the repository is not present
 * (for example inside a slim build container that only mounts the module).
 */
class CanonicalSchemaVerbatimTest {

    private static final Path DOCS = Path.of("..", "..", "..", "docs", "search", "schema");

    @ParameterizedTest
    @ValueSource(strings = {
            "catalog_upsert", "catalog_tombstone", "stock_patch", "review_metrics_patch",
            "category_upsert", "category_tombstone", "category_product_count"})
    void painlessScriptMatchesTheCanonicalFile(String name) throws IOException {
        Path canonical = DOCS.resolve("scripts").resolve(name + ".painless");
        assumeThat(Files.exists(canonical)).as("docs/search/schema is on disk").isTrue();

        assertThat(resource("search/scripts/" + name + ".painless"))
                .as("classpath copy of %s must be byte-identical to the canonical script", name)
                .isEqualTo(Files.readString(canonical, StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"products", "categories", "search_suggestions", "search_jobs"})
    void indexMappingMatchesTheCanonicalFile(String name) throws IOException {
        Path canonical = DOCS.resolve(name + ".index.json");
        assumeThat(Files.exists(canonical)).as("docs/search/schema is on disk").isTrue();

        assertThat(resource("search/schema/" + name + ".index.json"))
                .as("classpath copy of %s must be byte-identical to the canonical mapping", name)
                .isEqualTo(Files.readString(canonical, StandardCharsets.UTF_8));
    }

    /** Every mapping Search ships must be strict, or an unexpected field would be silent. */
    @Test
    void everyMappingIsDynamicStrict() throws IOException {
        for (String name : List.of("products", "categories", "search_suggestions", "search_jobs")) {
            assertThat(resource("search/schema/" + name + ".index.json"))
                    .as("%s must set dynamic: strict", name)
                    .contains("\"dynamic\": \"strict\"");
        }
    }

    private static String resource(String path) throws IOException {
        try (var in = CanonicalSchemaVerbatimTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("classpath resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
