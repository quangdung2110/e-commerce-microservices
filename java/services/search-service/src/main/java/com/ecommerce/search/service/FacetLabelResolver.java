package com.ecommerce.search.service;

import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.model.AttributeToken;
import com.ecommerce.search.repository.CategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Display names for facet groups, taken from the {@code facet_definitions} Product publishes on
 * every Category snapshot (Product ADR-019).
 *
 * <p>A facet aggregates tokens over a whole branch, so the labels come from the category and all
 * of its visible descendants. The attribute {@code code} is globally unique in Product and its
 * label belongs to the attribute, so the same code has the same label in every category; if two
 * snapshots ever disagree (a rename still propagating) the shallowest node, then the lowest id,
 * wins, which is deterministic and heals on the next snapshot.
 *
 * <p>Labels are cosmetic, so a read failure never fails the facet request: the caller falls back
 * to the label derived from the token. Results are cached briefly per scope because the search
 * endpoint would otherwise scan the categories index on every request; the key space is bounded
 * by the number of categories.
 */
@Component
public class FacetLabelResolver {

    private static final Logger log = LoggerFactory.getLogger(FacetLabelResolver.class);

    /** How long a rename may take to show up in facet labels. */
    static final Duration TTL = Duration.ofSeconds(60);

    private static final String ALL_CATEGORIES = "";

    private record Entry(Map<String, String> labels, long loadedAtNanos) {
    }

    private final CategoryRepository repository;
    private final LongSupplier nanoClock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public FacetLabelResolver(CategoryRepository repository) {
        this(repository, System::nanoTime);
    }

    FacetLabelResolver(CategoryRepository repository, LongSupplier nanoClock) {
        this.repository = repository;
        this.nanoClock = nanoClock;
    }

    /**
     * Labels keyed by the folded attribute code as it appears in {@code filter_tokens}.
     *
     * @param categoryId the branch the facets aggregate over, or empty for an unscoped search
     */
    public Map<String, String> labels(Optional<String> categoryId) {
        String scope = categoryId.orElse(ALL_CATEGORIES);
        long now = nanoClock.getAsLong();
        Entry cached = cache.get(scope);
        if (cached != null && now - cached.loadedAtNanos() < TTL.toNanos()) {
            return cached.labels();
        }
        try {
            List<CategoryDocument> documents = categoryId.isPresent()
                    ? repository.findBranch(scope)
                    : repository.findAllVisible();
            Map<String, String> labels = labelsOf(documents);
            cache.put(scope, new Entry(labels, now));
            return labels;
        } catch (Exception e) {
            log.warn("cannot read facet_definitions for scope '{}', falling back to derived labels", scope, e);
            return cached != null ? cached.labels() : Map.of();
        }
    }

    /** Drops every cached scope so the next request reads the categories index again. */
    public void evictAll() {
        cache.clear();
    }

    static Map<String, String> labelsOf(List<CategoryDocument> documents) {
        Map<String, String> labels = new HashMap<>();
        for (CategoryDocument document : documents) {
            for (CategoryDocument.FacetDefinition definition : document.facetDefinitionsOrEmpty()) {
                if (definition.code() == null || definition.label() == null || definition.label().isBlank()) {
                    continue;
                }
                labels.putIfAbsent(AttributeToken.foldCode(definition.code()), definition.label());
            }
        }
        return Map.copyOf(labels);
    }
}
