package com.ecommerce.search.worker.handler;

import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.repository.CategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Per-batch cache of the category projection used to enrich catalog events.
 *
 * <p>{@code WF-SEARCH-005} step 3 reads the category node to materialise ancestor ids and
 * slugs onto the SKU. Since one Kafka batch usually touches a handful of categories, the
 * lookups are cached for the batch — including the misses, so a category that is genuinely
 * absent is not queried once per SKU.
 */
@Component
public class CategoryEnrichment {

    private static final Logger log = LoggerFactory.getLogger(CategoryEnrichment.class);

    private final CategoryRepository repository;

    public CategoryEnrichment(CategoryRepository repository) {
        this.repository = repository;
    }

    /** A cache valid for exactly one batch; never held across polls. */
    public Cache newCache() {
        return new Cache();
    }

    public final class Cache {

        private final Map<String, Optional<CategoryDocument>> byId = new HashMap<>();

        private Cache() {
        }

        public Optional<CategoryDocument> lookup(String categoryId) {
            if (categoryId == null || categoryId.isBlank()) {
                return Optional.empty();
            }
            return byId.computeIfAbsent(categoryId, id -> {
                try {
                    return repository.findById(id);
                } catch (IOException e) {
                    // Treated as a miss: the document is still indexed without derived
                    // category fields rather than blocking the whole batch.
                    log.warn("category enrichment lookup for {} failed: {}", id, e.getMessage());
                    return Optional.empty();
                }
            });
        }
    }
}
