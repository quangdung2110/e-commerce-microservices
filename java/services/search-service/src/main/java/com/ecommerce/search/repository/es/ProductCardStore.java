package com.ecommerce.search.repository.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.config.ProductIndexProperties;
import com.ecommerce.search.worker.productcards.ProductCard;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Elasticsearch I/O of the {@code product_cards} projector (ADR-027): reading SKU documents from a
 * {@code products} generation and writing Product cards with external versions.
 */
@Component
public class ProductCardStore {

    private static final String PIT_KEEP_ALIVE = "2m";
    private static final String VERSION_CONFLICT = "version_conflict_engine_exception";

    private final ElasticsearchClient client;
    private final ProductIndexProperties properties;

    public ProductCardStore(ElasticsearchClient client, ProductIndexProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** A SKU document seen by the change feed. */
    public record Change(String productId, long indexedAtMillis, List<FieldValue> sort) {
    }

    /**
     * Walks every SKU document of one {@code products} generation ordered by {@code product_id}
     * then {@code sku_id}, handing each Product's complete set of documents to {@code sink}. The
     * walk runs on a point-in-time, so a Product split across pages is still read from one view.
     */
    public void scanAllProducts(String sourceIndex, Consumer<ProductCard> sink) throws IOException {
        scanAllProducts(sourceIndex, sink, () -> {});
    }

    public void scanAllProducts(String sourceIndex, Consumer<ProductCard> sink, Runnable checkpoint) throws IOException {
        String pit = client.openPointInTime(b -> b.index(sourceIndex).keepAlive(t -> t.time(PIT_KEEP_ALIVE))).id();
        try {
            List<FieldValue> after = null;
            String currentProduct = null;
            List<ObjectNode> currentSkus = new ArrayList<>();
            while (true) {
                checkpoint.run();
                List<FieldValue> cursor = after;
                String pitId = pit;
                SearchResponse<ObjectNode> page = client.search(b -> {
                    b.pit(p -> p.id(pitId).keepAlive(t -> t.time(PIT_KEEP_ALIVE)))
                            .size(properties.scanPageSize())
                            .trackTotalHits(t -> t.enabled(false))
                            .allowPartialSearchResults(false)
                            .sort(asc("product_id"), asc("sku_id"));
                    if (cursor != null) {
                        b.searchAfter(cursor);
                    }
                    return b;
                }, ObjectNode.class);
                if (page.pitId() != null) {
                    pit = page.pitId();
                }
                requireComplete(page, "product card scan");
                List<Hit<ObjectNode>> hits = page.hits().hits();
                if (hits.isEmpty()) {
                    break;
                }
                for (Hit<ObjectNode> hit : hits) {
                    ObjectNode sku = hit.source();
                    String productId = sku == null ? null : sku.path("product_id").asText(null);
                    if (productId == null) {
                        continue;
                    }
                    if (!productId.equals(currentProduct)) {
                        if (currentProduct != null) {
                            sink.accept(ProductCard.of(currentProduct, currentSkus));
                        }
                        currentProduct = productId;
                        currentSkus = new ArrayList<>();
                    }
                    currentSkus.add(sku);
                }
                after = hits.get(hits.size() - 1).sort();
            }
            if (currentProduct != null) {
                sink.accept(ProductCard.of(currentProduct, currentSkus));
            }
            checkpoint.run();
        } finally {
            closeQuietly(pit);
        }
    }

    /**
     * SKU documents written at or after {@code since}, oldest first, one page after {@code after}.
     * Every write script stamps {@code indexed_at}, so this is the change feed of the SKU index.
     */
    public List<Change> changes(String sourceIndex, Instant since, List<FieldValue> after) throws IOException {
        SearchResponse<ObjectNode> page = client.search(b -> {
            b.index(sourceIndex)
                    .size(properties.scanPageSize())
                    .trackTotalHits(t -> t.enabled(false))
                    .allowPartialSearchResults(false)
                    .source(s -> s.filter(f -> f.includes(List.of("product_id", "indexed_at"))))
                    .query(q -> q.range(r -> r.field("indexed_at").gte(JsonData.of(Timestamps.iso(since)))))
                    .sort(asc("indexed_at"), asc("sku_id"));
            if (after != null) {
                b.searchAfter(after);
            }
            return b;
        }, ObjectNode.class);
        requireComplete(page, "product card change feed");
        List<Change> changes = new ArrayList<>();
        for (Hit<ObjectNode> hit : page.hits().hits()) {
            ObjectNode source = hit.source();
            if (source != null && source.hasNonNull("product_id")) {
                changes.add(new Change(source.get("product_id").asText(),
                        ProductCard.epochMillis(source.path("indexed_at").asText(null)), hit.sort()));
            }
        }
        return changes;
    }

    /** Every SKU document of the given Products, grouped into cards. A Product with no document left has an empty card. */
    public List<ProductCard> loadProducts(String sourceIndex, Collection<String> productIds) throws IOException {
        List<ProductCard> cards = new ArrayList<>();
        List<FieldValue> after = null;
        String currentProduct = null;
        List<ObjectNode> currentSkus = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Query byProduct = Query.of(q -> q.terms(t -> t.field("product_id")
                .terms(v -> v.value(productIds.stream().map(FieldValue::of).toList()))));
        while (true) {
            List<FieldValue> cursor = after;
            SearchResponse<ObjectNode> page = client.search(b -> {
                b.index(sourceIndex)
                        .size(properties.scanPageSize())
                        .trackTotalHits(t -> t.enabled(false))
                        .allowPartialSearchResults(false)
                        .query(byProduct)
                        .sort(asc("product_id"), asc("sku_id"));
                if (cursor != null) {
                    b.searchAfter(cursor);
                }
                return b;
            }, ObjectNode.class);
            requireComplete(page, "product card rebuild");
            List<Hit<ObjectNode>> hits = page.hits().hits();
            if (hits.isEmpty()) {
                break;
            }
            for (Hit<ObjectNode> hit : hits) {
                ObjectNode sku = hit.source();
                String productId = sku == null ? null : sku.path("product_id").asText(null);
                if (productId == null) {
                    continue;
                }
                if (!productId.equals(currentProduct)) {
                    if (currentProduct != null) {
                        cards.add(ProductCard.of(currentProduct, currentSkus));
                    }
                    currentProduct = productId;
                    currentSkus = new ArrayList<>();
                    seen.add(productId);
                }
                currentSkus.add(sku);
            }
            after = hits.get(hits.size() - 1).sort();
        }
        if (currentProduct != null) {
            cards.add(ProductCard.of(currentProduct, currentSkus));
        }
        for (String productId : productIds) {
            if (!seen.contains(productId)) {
                cards.add(new ProductCard(productId, 0L, List.of()));
            }
        }
        return cards;
    }

    /**
     * Writes cards with {@code version_type=external_gte}: a card built from an older state than the
     * one stored loses with a version conflict, which is the intended outcome and not an error. A
     * card without live SKUs deletes the document under the same rule.
     */
    public void write(String index, List<ProductCard> cards) throws IOException {
        if (cards.isEmpty()) {
            return;
        }
        List<BulkOperation> operations = new ArrayList<>(cards.size());
        for (ProductCard card : cards) {
            if (card.deleted()) {
                operations.add(BulkOperation.of(op -> op.delete(d -> {
                    d.index(index).id(card.productId());
                    if (card.version() > 0) {
                        d.versionType(VersionType.ExternalGte).version(card.version());
                    }
                    return d;
                })));
            } else {
                operations.add(BulkOperation.of(op -> op.index(i -> i.index(index).id(card.productId())
                        .versionType(VersionType.ExternalGte).version(Math.max(1L, card.version()))
                        .document(card.document()))));
            }
        }
        BulkResponse response = client.bulk(b -> b.operations(operations));
        if (response.errors()) {
            for (BulkResponseItem item : response.items()) {
                if (item.error() != null && !VERSION_CONFLICT.equals(item.error().type())) {
                    throw new IOException("product card write failed for " + item.id() + ": "
                            + item.error().type() + " " + item.error().reason());
                }
            }
        }
    }

    private void closeQuietly(String pit) {
        try {
            client.closePointInTime(b -> b.id(pit));
        } catch (Exception ignored) {
            // keep-alive reclaims it
        }
    }

    private static SortOptions asc(String field) {
        return SortOptions.of(s -> s.field(f -> f.field(field).order(SortOrder.Asc)));
    }

    private static void requireComplete(SearchResponse<?> response, String what) throws IOException {
        if (response.timedOut() || (response.shards() != null && response.shards().failed().intValue() > 0)) {
            throw new IOException(what + " returned incomplete results");
        }
    }
}
