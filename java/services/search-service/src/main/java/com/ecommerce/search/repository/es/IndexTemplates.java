package com.ecommerce.search.repository.es;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the canonical index mappings from the classpath, byte-identical to
 * {@code docs/search/schema/*.index.json}.
 *
 * <p>The files carry {@code index_patterns} and an {@code aliases_note}, which are
 * documentation rather than settings; only {@code settings} and {@code mappings} are sent
 * to Elasticsearch. Aliases are attached explicitly at creation or cutover so two indices
 * can never hold the same write alias.
 */
@Component
public class IndexTemplates {

    public static final String PRODUCTS = "products";
    public static final String CATEGORIES = "categories";
    public static final String SEARCH_SUGGESTIONS = "search_suggestions";
    public static final String SEARCH_JOBS = "search_jobs";

    private final ObjectMapper objectMapper;
    private final Map<String, JsonNode> cache = new ConcurrentHashMap<>();

    public IndexTemplates(ObjectMapper esObjectMapper) {
        this.objectMapper = esObjectMapper;
    }

    public JsonNode settings(String template) {
        return require(template, "settings");
    }

    public JsonNode mappings(String template) {
        return require(template, "mappings");
    }

    private JsonNode require(String template, String section) {
        JsonNode node = load(template).get(section);
        if (node == null || node.isNull()) {
            throw new IllegalStateException("index template " + template + " has no '" + section + "' section");
        }
        return node;
    }

    private JsonNode load(String template) {
        return cache.computeIfAbsent(template, name -> {
            ClassPathResource resource = new ClassPathResource("search/schema/" + name + ".index.json");
            try (var in = resource.getInputStream()) {
                return objectMapper.readTree(in);
            } catch (IOException e) {
                throw new UncheckedIOException("index template " + name + " is missing from the artifact", e);
            }
        });
    }
}
