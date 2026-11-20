package com.ecommerce.search.worker.job;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.repository.es.IndexAdmin;
import com.ecommerce.search.repository.es.ProjectionWriter;
import com.ecommerce.search.repository.es.ThrottledBulkWriter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.List;

/** Carries deletion guards across generations; an export of existing catalog rows cannot do so. */
@Component
public class ReindexTombstoneTransfer {
    private static final Logger log = LoggerFactory.getLogger(ReindexTombstoneTransfer.class);
    private final ElasticsearchClient client;
    private final IndexAdmin indices;
    private final ProjectionWriter writer;
    private final ThrottledBulkWriter bulk;
    private final SearchProperties properties;
    private final ObjectMapper mapper;

    public ReindexTombstoneTransfer(ElasticsearchClient client, IndexAdmin indices, ProjectionWriter writer,
                                    ThrottledBulkWriter bulk, SearchProperties properties, ObjectMapper esObjectMapper) {
        this.client = client;
        this.indices = indices;
        this.writer = writer;
        this.bulk = bulk;
        this.properties = properties;
        this.mapper = esObjectMapper;
    }

    public void preserve(String products, String categories, Instant deadline, Runnable checkpoint) throws IOException {
        copy(properties.aliases().products(), products, "sku_id", "catalog_revision", deadline, checkpoint);
        copy(properties.aliases().categories(), categories, "category_id", "category_revision", deadline, checkpoint);
    }

    private void copy(String alias, String target, String idField, String revisionField,
                      Instant deadline, Runnable checkpoint) throws IOException {
        var source = indices.resolveAlias(alias);
        if (source.isEmpty() || source.get().equals(target)) return;
        // Refresh the source so a recently acknowledged delete is not missed by the scan.
        indices.refresh(source.get());
        String pit = client.openPointInTime(b -> b.index(source.get()).keepAlive(t -> t.time("1m"))).id();
        List<FieldValue> after = null;
        int size = properties.reindex().exportPageSize();
        try (var session = bulk.open("reindex-tombstones", deadline)) {
            while (true) {
                checkpoint.run();
                final List<FieldValue> cursor = after;
                final String pagePit = pit;
                SearchResponse<JsonNode> page = client.search(b -> {
                    b.pit(p -> p.id(pagePit).keepAlive(t -> t.time("1m"))).size(size).allowPartialSearchResults(false)
                            .timeout(properties.query().productTimeout().toMillis() + "ms")
                            .trackTotalHits(t -> t.enabled(false))
                            .query(q -> q.term(t -> t.field("is_deleted").value(true)))
                            .sort(s -> s.field(f -> f.field(idField).order(SortOrder.Asc)));
                    if (cursor != null) b.searchAfter(cursor);
                    return b;
                }, JsonNode.class);
                if (page.pitId() != null) pit = page.pitId();
                if (Boolean.TRUE.equals(page.timedOut()) || page.shards().failed().intValue() != 0) {
                    throw new IOException("incomplete tombstone scan on " + source.get());
                }
                for (var hit : page.hits().hits()) {
                    JsonNode doc = hit.source();
                    if (doc == null || !doc.path("is_deleted").asBoolean()
                            || !doc.path(revisionField).isIntegralNumber() || !doc.hasNonNull("deleted_at")
                            || !hit.id().equals(doc.path(idField).asText())) {
                        throw new IOException("invalid tombstone " + hit.id() + " on " + source.get());
                    }
                    Map<String, Object> fields = mapper.convertValue(doc, new TypeReference<>() {});
                    session.add(writer.preserveTombstone(target, hit.id(), fields, revisionField), doc.toString().length() * 2L);
                    after = hit.sort();
                }
                session.flush();
                if (!session.failures().isEmpty()) throw new IOException("tombstone transfer failed: " + session.failures());
                if (page.hits().hits().size() < size) break;
            }
        } finally {
            final String lastPit = pit;
            try {
                client.closePointInTime(b -> b.id(lastPit));
            } catch (IOException | RuntimeException e) {
                log.warn("could not close tombstone scan PIT on {}: {}", source.get(), e.getMessage());
            }
        }
        indices.refresh(target);
    }
}
