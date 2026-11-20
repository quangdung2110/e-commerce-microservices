package com.ecommerce.search.repository.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Index lifecycle: create a versioned index, resolve an alias to its physical index and
 * cut over aliases atomically.
 *
 * <p>Physical names always carry a version suffix ({@code products_v1}) and the
 * application is only ever configured with alias names ({@code SEARCH-INV-007}). Cutover
 * is one {@code _aliases} call so no two indices can hold the same write alias at any
 * instant ({@code docs/search/data.md §6}).
 */
@Component
public class IndexAdmin {

    private static final Logger log = LoggerFactory.getLogger(IndexAdmin.class);
    private static final Pattern VERSIONED = Pattern.compile("^(?<base>.+)_v(?<version>\\d+)$");

    private final ElasticsearchClient client;
    private final IndexTemplates templates;

    public IndexAdmin(ElasticsearchClient client, IndexTemplates templates) {
        this.client = client;
        this.templates = templates;
    }

    /** Creates {@code <template>_v<version>} from the canonical mapping. */
    public String createVersionedIndex(String template, int version) throws IOException {
        String index = physicalName(template, version);
        CreateIndexRequest request = CreateIndexRequest.of(builder -> builder
                .index(index)
                .withJson(new StringReader("{\"settings\":" + templates.settings(template)
                        + ",\"mappings\":" + templates.mappings(template) + "}")));
        client.indices().create(request);
        log.info("created index {} from canonical template {}", index, template);
        return index;
    }

    public static String physicalName(String template, int version) {
        return template + "_v" + version;
    }

    /** Parses the version suffix of a physical index name. */
    public static Optional<Integer> versionOf(String physicalIndex) {
        Matcher matcher = VERSIONED.matcher(physicalIndex);
        return matcher.matches() ? Optional.of(Integer.parseInt(matcher.group("version"))) : Optional.empty();
    }

    public boolean indexExists(String index) throws IOException {
        return client.indices().exists(builder -> builder.index(index)).value();
    }

    public boolean aliasExists(String alias) throws IOException {
        return client.indices().existsAlias(builder -> builder.name(alias)).value();
    }

    /**
     * Resolves an alias to the single physical index behind it. A read alias pointing at
     * more than one index is a configuration error, not something to guess around.
     */
    public Optional<String> resolveAlias(String alias) throws IOException {
        if (!aliasExists(alias)) {
            return Optional.empty();
        }
        GetAliasResponse response = client.indices().getAlias(builder -> builder.name(alias));
        List<String> indices = new ArrayList<>(response.result().keySet());
        if (indices.isEmpty()) {
            return Optional.empty();
        }
        if (indices.size() > 1) {
            throw new IllegalStateException("alias " + alias + " points at " + indices
                    + "; exactly one physical index is required");
        }
        return Optional.of(indices.get(0));
    }

    /** Highest existing version for a template, or empty when nothing has been built yet. */
    public Optional<Integer> highestVersion(String template) throws IOException {
        var indices = client.indices().get(builder -> builder
                .index(template + "_v*")
                .ignoreUnavailable(true)
                .allowNoIndices(true));
        return indices.result().keySet().stream()
                .map(IndexAdmin::versionOf)
                .flatMap(Optional::stream)
                .max(Comparator.naturalOrder());
    }

    /** Next version to build: one above whatever exists, starting at 1. */
    public int nextVersion(String template) throws IOException {
        return highestVersion(template).orElse(0) + 1;
    }

    /**
     * Moves every given alias onto {@code targetIndex} in one atomic {@code _aliases}
     * call, removing it from whatever index currently holds it.
     */
    public void swapAliases(String targetIndex, List<String> aliases) throws IOException {
        List<Action> actions = new ArrayList<>();
        for (String alias : aliases) {
            actions.add(Action.of(a -> a.remove(r -> r.index("*").alias(alias).mustExist(false))));
        }
        for (String alias : aliases) {
            actions.add(Action.of(a -> a.add(add -> add.index(targetIndex).alias(alias))));
        }
        client.indices().updateAliases(builder -> builder.actions(actions));
        log.info("alias cutover: {} now serve {}", aliases, targetIndex);
    }

    /**
     * Moves every alias of several indices in one {@code _aliases} request, so there is no
     * instant at which the product and category aliases point at different generations, nor
     * two indices holding the same write alias.
     *
     * @param targets physical index to the aliases it must hold afterwards
     */
    public void swapAliasesAtomically(Map<String, List<String>> targets) throws IOException {
        List<Action> actions = new ArrayList<>();
        targets.values().forEach(aliases -> aliases.forEach(alias ->
                actions.add(Action.of(a -> a.remove(r -> r.index("*").alias(alias).mustExist(false))))));
        targets.forEach((index, aliases) -> aliases.forEach(alias ->
                actions.add(Action.of(a -> a.add(add -> add.index(index).alias(alias))))));
        client.indices().updateAliases(builder -> builder.actions(actions));
        log.info("atomic alias cutover: {}", targets);
    }

    /** Every physical index of a template, {@code <template>_v<n>}, in no particular order. */
    public List<String> versionedIndices(String template) throws IOException {
        var indices = client.indices().get(builder -> builder
                .index(template + "_v*")
                .ignoreUnavailable(true)
                .allowNoIndices(true));
        return indices.result().keySet().stream()
                .filter(name -> VERSIONED.matcher(name).matches())
                .filter(name -> name.substring(0, name.lastIndexOf("_v")).equals(template))
                .toList();
    }

    /** Store size of the primary shards of an index or alias; 0 when it does not exist. */
    public long primaryStoreBytes(String indexOrAlias) throws IOException {
        try {
            var stats = client.indices().stats(builder -> builder.index(indexOrAlias).metric("store"));
            var primaries = stats.all().primaries();
            return primaries == null || primaries.store() == null ? 0L : primaries.store().sizeInBytes();
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            if (e.status() == 404) {
                return 0L;
            }
            throw e;
        }
    }

    /**
     * Reads one document from an explicit physical index. Used by the reindex build, which
     * must enrich from the index it just created rather than from the live alias.
     */
    public <T> Optional<T> getDocument(String index, String id, Class<T> type) throws IOException {
        var response = client.get(builder -> builder.index(index).id(id), type);
        return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
    }

    public void deleteIndex(String index) throws IOException {
        client.indices().delete(builder -> builder.index(index).ignoreUnavailable(true));
        log.info("deleted index {}", index);
    }

    public void refresh(String indexOrAlias) throws IOException {
        client.indices().refresh(builder -> builder.index(indexOrAlias));
    }

    public long countDocuments(String indexOrAlias) throws IOException {
        return client.count(builder -> builder.index(indexOrAlias)).count();
    }

    /** A retired cards generation must outlive PITs that buyers are still renewing. */
    public boolean hasOpenSearchContexts(String index) throws IOException {
        var stats = client.indices().stats(b -> b.index(index).metric("search"));
        if (stats.shards().failed().intValue() > 0) throw new IOException("incomplete search-context stats for " + index);
        var total = stats.all().total();
        return total == null || total.search() == null || total.search().openContexts() == null
                || total.search().openContexts() > 0;
    }

    /** Compare catalog coverage independently of retained/purged deletion guards. */
    public long countNonDeletedDocuments(String index) throws IOException {
        var response = client.count(b -> b.index(index)
                .query(q -> q.term(t -> t.field("is_deleted").value(false))));
        if (response.shards().failed().intValue() != 0) throw new IOException("incomplete catalog count on " + index);
        return response.count();
    }
}
