package com.ecommerce.search.repository.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonData;
import com.ecommerce.search.config.SearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lifecycle of the {@code product_cards} projection (ADR-027): one physical generation
 * {@code product_cards_v<N>} behind the {@code product_cards} alias, built from one
 * {@code products} generation whose name it records in {@code _meta.source_index}.
 *
 * <p>The projection is only trusted while that name is the index the {@code products} alias
 * resolves to. Full reindex prepares both generations before a paired atomic cutover (ADR-029).
 * A mismatch from bootstrap or an out-of-band alias change suspends new searches until repaired.
 */
@Component
public class ProductCardIndex {

    public static final String ALIAS = IndexTemplates.PRODUCT_CARDS;
    static final String SOURCE_INDEX_META = "source_index";
    static final String CHANGES_THROUGH_META = "changes_through";

    private static final Logger log = LoggerFactory.getLogger(ProductCardIndex.class);

    private final ElasticsearchClient client;
    private final IndexAdmin indexAdmin;
    private final SearchProperties properties;

    public ProductCardIndex(ElasticsearchClient client, IndexAdmin indexAdmin, SearchProperties properties) {
        this.client = client;
        this.indexAdmin = indexAdmin;
        this.properties = properties;
    }

    /** The physical {@code products} index the SKU read alias serves now. */
    public Optional<String> currentSourceIndex() throws IOException {
        return indexAdmin.resolveAlias(properties.aliases().products());
    }

    /** The {@code products} generation the live projection mirrors, or empty when there is none. */
    public Optional<String> mirroredSourceIndex() throws IOException {
        return meta(SOURCE_INDEX_META);
    }

    public Optional<String> currentGeneration() throws IOException {
        return indexAdmin.resolveAlias(ALIAS);
    }

    private Optional<String> meta(String key) throws IOException {
        Optional<String> physical = indexAdmin.resolveAlias(ALIAS);
        if (physical.isEmpty()) {
            return Optional.empty();
        }
        var mapping = client.indices().getMapping(builder -> builder.index(physical.get()))
                .result().get(physical.get());
        if (mapping == null || mapping.mappings().meta() == null) {
            return Optional.empty();
        }
        JsonData value = mapping.mappings().meta().get(key);
        return value == null ? Optional.empty() : Optional.of(value.to(String.class));
    }

    /**
     * The change-feed position the projector had fully applied when it last recorded one, so a
     * restarted or newly elected projector resumes there instead of skipping the gap.
     */
    public Optional<Instant> recordedProgress() throws IOException {
        return meta(CHANGES_THROUGH_META).map(Instant::parse);
    }

    /** Always pin writes to the physical generation a projector owns. */
    public void recordProgress(String target, String sourceIndex, Instant changesThrough) throws IOException {
        client.indices().putMapping(builder -> builder.index(target).meta(Map.of(
                SOURCE_INDEX_META, JsonData.of(sourceIndex),
                CHANGES_THROUGH_META, JsonData.of(changesThrough.toString()))));
    }

    /** True when the live projection was built from the generation the SKU alias serves now. */
    public boolean mirrorsCurrentSource() throws IOException {
        // Resolve BOTH aliases from one cluster-state response. Separate lookups could
        // falsely report a mismatch while an atomic cutover moves both generations.
        try {
            var aliases = client.indices().getAlias(b -> b.name(List.of(properties.aliases().products(), ALIAS)));
            String source = null;
            String cards = null;
            for (var entry : aliases.result().entrySet()) {
                if (entry.getValue().aliases().containsKey(properties.aliases().products())) source = entry.getKey();
                if (entry.getValue().aliases().containsKey(ALIAS)) cards = entry.getKey();
            }
            if (source == null || cards == null) return false;
            String physical = cards;
            var mapping = client.indices().getMapping(b -> b.index(physical)).result().get(physical);
            JsonData recorded = mapping.mappings().meta().get(SOURCE_INDEX_META);
            return recorded != null && source.equals(recorded.to(String.class));
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            if (e.status() == 404) return false;
            throw e;
        }
    }

    /**
     * Creates the next generation for a full build: refresh off while it is filled, and the source
     * generation it is built from recorded before any document lands, so the name travels with it.
     */
    public String createGeneration(String sourceIndex) throws IOException {
        String index = indexAdmin.createVersionedIndex(IndexTemplates.PRODUCT_CARDS,
                indexAdmin.nextVersion(IndexTemplates.PRODUCT_CARDS));
        client.indices().putMapping(builder -> builder.index(index)
                .meta(Map.of(SOURCE_INDEX_META, JsonData.of(sourceIndex))));
        setRefreshInterval(index, "-1");
        return index;
    }

    /** Bootstrap publication. Reindex publishes this alias with SKU/Category in one atomic swap. */
    public void publish(String index) throws IOException {
        prepare(index);
        indexAdmin.swapAliases(index, List.of(ALIAS));
        // Superseded generations belong to IndexCleanupJob's retirement window:
        // deleting here invalidates already-open product-card PITs.
    }

    public void prepare(String index) throws IOException {
        setRefreshInterval(index, "1s");
        indexAdmin.refresh(index);
    }

    /** Drops a generation whose build failed, so the next attempt starts clean. */
    public void discard(String index) {
        try {
            if (currentGeneration().filter(index::equals).isEmpty()) indexAdmin.deleteIndex(index);
        } catch (IOException | RuntimeException e) {
            log.warn("could not drop abandoned product card generation {}: {}", index, e.toString());
        }
    }

    private void setRefreshInterval(String index, String interval) throws IOException {
        client.indices().putSettings(builder -> builder.index(index)
                .settings(settings -> settings.refreshInterval(time -> time.time(interval))));
    }
}
