package com.ecommerce.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.InnerHitsResult;
import co.elastic.clients.json.JsonData;
import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.repository.es.ProductCardIndex;
import com.ecommerce.search.service.ProductCardQueryBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code product_cards} (ADR-027): one page of Products per request, natively sorted and
 * counted by Elasticsearch, bound to a point-in-time so the cursor sees one snapshot.
 */
@Repository
public class ProductCardRepository {

    private final ElasticsearchClient client;
    private final SearchProperties properties;
    private final SearchMetrics metrics;
    private final String keepAlive;

    public ProductCardRepository(ElasticsearchClient client, SearchProperties properties, SearchMetrics metrics,
                                 @Value("${search.pagination.pit-keep-alive:2m}") String keepAlive) {
        this.client = client;
        this.properties = properties;
        this.metrics = metrics;
        this.keepAlive = keepAlive;
    }

    /** One matching Product: its representative SKU and the sort values that locate it for {@code search_after}. */
    public record Card(ProductDocument representative, List<FieldValue> sort) {
    }

    public record Page(List<Card> cards, long totalProducts, Map<String, Aggregate> aggregations, String pitId) {
    }

    public String openSnapshot() throws IOException {
        return client.openPointInTime(b -> b.index(ProductCardIndex.ALIAS).keepAlive(t -> t.time(keepAlive))).id();
    }

    /** Best effort close. Abandoned pagination expires automatically after keepAlive. */
    public void closeSnapshot(String pit) {
        try {
            client.closePointInTime(b -> b.id(pit));
        } catch (Exception ignored) {
            // TTL reclaims it
        }
    }

    public Page search(Query query, List<SortOptions> sort, Map<String, Aggregation> aggregations, int size,
                       String pit, List<FieldValue> after) throws IOException {
        long started = System.nanoTime();
        try {
            SearchResponse<Void> response = client.search(b -> {
                b.pit(p -> p.id(pit).keepAlive(t -> t.time(keepAlive)))
                        .query(query)
                        .sort(sort)
                        .size(size)
                        .source(s -> s.fetch(false))
                        .trackTotalHits(t -> t.enabled(true))
                        .allowPartialSearchResults(false)
                        .timeout(properties.query().productTimeout().toMillis() + "ms")
                        .aggregations(aggregations);
                if (after != null) {
                    b.searchAfter(after);
                }
                return b;
            }, Void.class);
            SearchCompleteness.require(response, "product cards");
            List<Card> cards = new ArrayList<>(response.hits().hits().size());
            for (Hit<Void> hit : response.hits().hits()) {
                cards.add(new Card(representative(hit), hit.sort()));
            }
            long total = response.hits().total() == null ? cards.size() : response.hits().total().value();
            return new Page(cards, total, response.aggregations(), response.pitId() == null ? pit : response.pitId());
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                throw SearchException.invalidCursor("search snapshot expired; restart pagination");
            }
            throw e;
        } finally {
            metrics.esQuery(ProductCardIndex.ALIAS, "product_cards", Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private static ProductDocument representative(Hit<Void> hit) throws IOException {
        InnerHitsResult inner = hit.innerHits().get(ProductCardQueryBuilder.REPRESENTATIVE);
        if (inner == null || inner.hits().hits().isEmpty() || inner.hits().hits().get(0).source() == null) {
            throw new IOException("product card " + hit.id() + " has no representative SKU");
        }
        JsonData source = inner.hits().hits().get(0).source();
        ProductDocument doc = source.to(ProductDocument.class);
        if (doc.sellingPriceVnd() == null || doc.skuId() == null || doc.productId() == null) {
            throw new IOException("product card " + hit.id() + " has an incomplete representative SKU");
        }
        return doc;
    }
}
