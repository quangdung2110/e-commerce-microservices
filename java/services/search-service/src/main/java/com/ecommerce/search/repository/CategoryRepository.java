package com.ecommerce.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.document.CategoryDocument;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads of the {@code categories} projection, always through the read alias. */
@Repository
public class CategoryRepository {

    /** Page size of the {@code search_after} scan used to read the whole tree. */
    private static final int SCAN_PAGE_SIZE = 1000;

    private final ElasticsearchClient client;
    private final SearchProperties properties;
    private final SearchMetrics metrics;

    public CategoryRepository(ElasticsearchClient client, SearchProperties properties, SearchMetrics metrics) {
        this.client = client;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Loads every category that is not tombstoned, ordered by {@code level} then
     * {@code category_id} so the caller can build the tree parent-before-child in one pass
     * ({@code WF-SEARCH-003}).
     */
    public List<CategoryDocument> findAllVisible() throws IOException {
        return scan(notDeleted(), "category_tree");
    }

    /**
     * Loads a category and every visible descendant (the node is in its own {@code ancestor_ids}),
     * which is the set of categories whose products a branch facet aggregates over.
     */
    public List<CategoryDocument> findBranch(String categoryId) throws IOException {
        Query branch = Query.of(q -> q.bool(bool -> bool
                .filter(notDeleted())
                .filter(filter -> filter.term(t -> t.field("ancestor_ids").value(categoryId)))));
        return scan(branch, "category_branch");
    }

    private List<CategoryDocument> scan(Query query, String queryName) throws IOException {
        List<CategoryDocument> all = new ArrayList<>();
        List<FieldValue> searchAfter = null;
        long startedAt = System.nanoTime();

        while (true) {
            List<FieldValue> cursor = searchAfter;
            SearchResponse<CategoryDocument> response = client.search(builder -> {
                builder.index(properties.aliases().categories())
                        .size(SCAN_PAGE_SIZE)
                        .trackTotalHits(track -> track.enabled(false))
                        .query(query)
                        .sort(sort -> sort.field(f -> f.field("level").order(SortOrder.Asc)))
                        .sort(sort -> sort.field(f -> f.field("category_id").order(SortOrder.Asc)))
                        .timeout(properties.query().categoryTimeout().toMillis() + "ms");
                if (cursor != null) {
                    builder.searchAfter(cursor);
                }
                return builder;
            }, CategoryDocument.class);

            var hits = response.hits().hits();
            hits.forEach(hit -> {
                if (hit.source() != null) {
                    all.add(hit.source());
                }
            });
            if (hits.size() < SCAN_PAGE_SIZE) {
                break;
            }
            searchAfter = hits.get(hits.size() - 1).sort();
        }

        metrics.esQuery(properties.aliases().categories(), queryName,
                Duration.ofNanos(System.nanoTime() - startedAt));
        return all;
    }

    /** Looks a category up by id, ignoring tombstoned nodes. */
    public Optional<CategoryDocument> findById(String categoryId) throws IOException {
        return findOneBy(Query.of(q -> q.term(t -> t.field("category_id").value(categoryId))));
    }

    /** Looks a category up by slug, so the {@code category} query parameter accepts both. */
    public Optional<CategoryDocument> findBySlug(String slug) throws IOException {
        return findOneBy(Query.of(q -> q.term(t -> t.field("slug").value(slug))));
    }

    private Optional<CategoryDocument> findOneBy(Query matcher) throws IOException {
        SearchResponse<CategoryDocument> response = client.search(builder -> builder
                .index(properties.aliases().categories())
                .size(1)
                .trackTotalHits(track -> track.enabled(false))
                .query(q -> q.bool(bool -> bool.filter(notDeleted()).filter(matcher)))
                .timeout(properties.query().categoryTimeout().toMillis() + "ms"),
                CategoryDocument.class);
        return response.hits().hits().stream().findFirst().map(hit -> hit.source());
    }

    private static Query notDeleted() {
        return Query.of(q -> q.bool(bool -> bool
                .mustNot(mustNot -> mustNot.term(t -> t.field("is_deleted").value(true)))));
    }
}
