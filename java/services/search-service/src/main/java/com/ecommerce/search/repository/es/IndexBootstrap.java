package com.ecommerce.search.repository.es;

import com.ecommerce.search.config.SearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Creates the version-1 indices and attaches their aliases on first start.
 *
 * <p>Disabled by default. In a shared environment the indices are created deliberately
 * from {@code docs/search/schema}, because {@code dynamic: strict} means an accidentally
 * auto-created index would make every write fail loudly rather than quietly generate a
 * wrong mapping ({@code docs/search/development.md §2}). Local development and integration
 * tests turn it on.
 *
 * <p>The internal {@code search_jobs} index is always created: it holds the state the jobs
 * need before any index lifecycle work can start.
 */
@Component
public class IndexBootstrap {

    private static final Logger log = LoggerFactory.getLogger(IndexBootstrap.class);

    private final IndexAdmin indexAdmin;
    private final SearchProperties properties;

    @Value("${search.bootstrap-indices:false}")
    private boolean bootstrapEnabled;

    public IndexBootstrap(IndexAdmin indexAdmin, SearchProperties properties) {
        this.indexAdmin = indexAdmin;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        try {
            ensure(IndexTemplates.SEARCH_JOBS, List.of(properties.aliases().searchJobs()));
            if (!bootstrapEnabled) {
                return;
            }
            ensure(IndexTemplates.PRODUCTS,
                    List.of(properties.aliases().products(), properties.aliases().productsWrite()));
            ensure(IndexTemplates.CATEGORIES,
                    List.of(properties.aliases().categories(), properties.aliases().categoriesWrite()));
            ensure(IndexTemplates.SEARCH_SUGGESTIONS, List.of(properties.aliases().searchSuggestions()));
        } catch (IOException e) {
            // Startup does not fail here: readiness already reports Elasticsearch as down,
            // and the instance will be kept out of traffic.
            log.error("index bootstrap failed: {}", e.getMessage());
        }
    }

    private void ensure(String template, List<String> aliases) throws IOException {
        if (indexAdmin.aliasExists(aliases.get(0))) {
            return;
        }
        String index = IndexAdmin.physicalName(template, 1);
        if (!indexAdmin.indexExists(index)) {
            indexAdmin.createVersionedIndex(template, 1);
        }
        indexAdmin.swapAliases(index, aliases);
        log.info("bootstrapped {} with aliases {}", index, aliases);
    }
}
