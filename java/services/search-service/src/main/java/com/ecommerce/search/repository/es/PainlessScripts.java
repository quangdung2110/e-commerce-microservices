package com.ecommerce.search.repository.es;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the canonical write scripts verbatim from the classpath.
 *
 * <p>{@code docs/search/schema/scripts/README.md} requires these files to be used exactly
 * as published, so the resources under {@code search/scripts/} are byte-for-byte copies of
 * {@code docs/search/schema/scripts/} — {@code PainlessScriptsAreVerbatimTest} fails the
 * build if they ever drift. No caller is allowed to edit its own copy of a script.
 */
@Component
public class PainlessScripts {
    public static final String REINDEX_TOMBSTONE_COPY = "reindex_tombstone_copy";

    public static final String CATALOG_UPSERT = "catalog_upsert";
    public static final String CATALOG_TOMBSTONE = "catalog_tombstone";
    public static final String AVAILABILITY_PATCH = "availability_patch";
    public static final String REVIEW_METRICS_PATCH = "review_metrics_patch";
    public static final String CATEGORY_UPSERT = "category_upsert";
    public static final String CATEGORY_TOMBSTONE = "category_tombstone";
    public static final String CATEGORY_PRODUCT_COUNT = "category_product_count";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** Returns the script source, loaded once and then held in memory. */
    public String source(String name) {
        return cache.computeIfAbsent(name, PainlessScripts::read);
    }

    private static String read(String name) {
        ClassPathResource resource = new ClassPathResource("search/scripts/" + name + ".painless");
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("canonical painless script " + name + " is missing from the artifact", e);
        }
    }
}
