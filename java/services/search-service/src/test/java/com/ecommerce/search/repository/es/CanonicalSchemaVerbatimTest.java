package com.ecommerce.search.repository.es;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
 * byte-identical to the published files. The whole class is skipped when the repository is
 * not present (for example inside a slim build container that only mounts the module), but
 * a <em>single</em> missing file while the rest of the design is on disk is a failure: it
 * means the artifact still ships something the design has withdrawn.
 */
class CanonicalSchemaVerbatimTest {

    private static final Path DOCS = Path.of("..", "..", "..", "docs", "search", "schema");

    private static final List<String> SCRIPTS = List.of(
            "catalog_upsert", "catalog_tombstone", "availability_patch", "review_metrics_patch",
            "category_upsert", "category_tombstone", "category_product_count");

    private static List<String> scripts() {
        return SCRIPTS;
    }

    @ParameterizedTest
    @MethodSource("scripts")
    void painlessScriptMatchesTheCanonicalFile(String name) throws IOException {
        assumeThat(Files.isDirectory(DOCS)).as("docs/search/schema is on disk").isTrue();
        Path canonical = DOCS.resolve("scripts").resolve(name + ".painless");

        assertThat(canonical)
                .as("%s is still a published canonical script", name)
                .exists();
        assertThat(resource("search/scripts/" + name + ".painless"))
                .as("classpath copy of %s must be byte-identical to the canonical script", name)
                .isEqualTo(Files.readString(canonical, StandardCharsets.UTF_8));
    }

    /**
     * The per-script check cannot see a script that was withdrawn from the design: the
     * artifact would keep shipping it, and a caller could keep invoking it, with no test
     * naming it. This closes that gap by comparing the two directories as sets.
     */
    @Test
    void theShippedScriptsAreExactlyTheOnesTheDesignPublishes() throws IOException {
        assumeThat(Files.isDirectory(DOCS)).as("docs/search/schema is on disk").isTrue();

        List<String> expected = SCRIPTS.stream().map(name -> name + ".painless").toList();
        try (var files = Files.list(DOCS.resolve("scripts"))) {
            assertThat(files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".painless"))
                    .toList())
                    .as("every published script must be shipped and tested, and no other")
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
        try (var files = Files.list(Path.of("src", "main", "resources", "search", "scripts"))) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .as("the artifact must not ship a script the design has withdrawn")
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"products", "categories", "search_suggestions", "search_jobs"})
    void indexMappingMatchesTheCanonicalFile(String name) throws IOException {
        assumeThat(Files.isDirectory(DOCS)).as("docs/search/schema is on disk").isTrue();
        Path canonical = DOCS.resolve(name + ".index.json");

        assertThat(canonical)
                .as("%s is still a published canonical mapping", name)
                .exists();
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
