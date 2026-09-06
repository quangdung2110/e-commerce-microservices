package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.model.SearchCursor;
import com.ecommerce.search.domain.model.SearchQuery;
import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.repository.CategoryRepository;
import com.ecommerce.search.repository.ProductSearchRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * {@code WF-SEARCH-001} — the core storefront query.
 *
 * <p>Pure read: validate, run one Elasticsearch query, transform, respond. No side effect,
 * no application cache and no internal retry; the client decides whether to try again.
 */
@Service
public class ProductSearchService {

    private final ProductSearchRepository repository;
    private final CategoryRepository categoryRepository;
    private final ProductQueryBuilder queryBuilder;
    private final FacetAssembler facetAssembler;
    private final ThumbnailUrlResolver thumbnails;
    private final SearchCursor cursors;

    public ProductSearchService(ProductSearchRepository repository,
                                CategoryRepository categoryRepository,
                                ProductQueryBuilder queryBuilder,
                                FacetAssembler facetAssembler,
                                ThumbnailUrlResolver thumbnails,
                                SearchCursor cursors) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.queryBuilder = queryBuilder;
        this.facetAssembler = facetAssembler;
        this.thumbnails = thumbnails;
        this.cursors = cursors;
    }

    public SearchDtos.SearchPage search(SearchQuery request) throws IOException {
        Optional<String> categoryId = resolveCategory(request.category());
        Query query = queryBuilder.build(request, categoryId);
        List<SortOptions> sort = queryBuilder.sortOptions(request.sort());

        List<FieldValue> searchAfter = request.cursor()
                .map(cursor -> cursors.decode(request, cursor))
                .orElse(null);

        SearchResponse<ProductDocument> response = repository.search(
                query, sort, queryBuilder.facetAggregations(), request.size(), searchAfter);

        List<Hit<ProductDocument>> hits = response.hits().hits();
        List<SearchDtos.SearchProductItem> items = hits.stream()
                .map(Hit::source)
                .filter(java.util.Objects::nonNull)
                .map(this::toItem)
                .toList();

        String nextCursor = nextCursor(request, hits);
        long totalSkus = response.hits().total() == null ? items.size() : response.hits().total().value();

        return new SearchDtos.SearchPage(
                items,
                totalSkus,
                nextCursor,
                facetAssembler.assemble(response.aggregations().get(ProductQueryBuilder.FACET_AGG)));
    }

    /**
     * A full page means there may be more; a short page is the end of the result set and
     * yields {@code next_cursor = null}.
     */
    private String nextCursor(SearchQuery request, List<Hit<ProductDocument>> hits) {
        if (hits.size() < request.size() || hits.isEmpty()) {
            return null;
        }
        List<FieldValue> sortValues = hits.get(hits.size() - 1).sort();
        return sortValues == null || sortValues.isEmpty() ? null : cursors.encode(request, sortValues);
    }

    /**
     * Resolves the {@code category} parameter, which the contract lets clients pass as an
     * id or a slug. An unknown value is a client error rather than a silent full-catalog
     * search, which would otherwise return a confusingly large result set.
     */
    private Optional<String> resolveCategory(Optional<String> category) throws IOException {
        if (category.isEmpty()) {
            return Optional.empty();
        }
        String raw = category.get().trim();
        Optional<CategoryDocument> found = categoryRepository.findById(raw);
        if (found.isEmpty()) {
            found = categoryRepository.findBySlug(raw);
        }
        return Optional.of(found
                .map(CategoryDocument::categoryId)
                .orElseThrow(() -> SearchException.invalidParameter("category",
                        "no visible category matches this id or slug")));
    }

    private SearchDtos.SearchProductItem toItem(ProductDocument doc) {
        return new SearchDtos.SearchProductItem(
                doc.skuId(),
                doc.productId(),
                doc.productSlug(),
                doc.name(),
                thumbnails.resolve(doc.thumbnailStorageKey()),
                doc.basePriceVnd() == null ? 0L : doc.basePriceVnd(),
                doc.sellingPriceVnd() == null ? 0L : doc.sellingPriceVnd(),
                DiscountCalculator.discountPercentage(doc.basePriceVnd(), doc.sellingPriceVnd()),
                doc.productRatingAvg(),
                doc.productRatingCount() == null ? 0L : doc.productRatingCount(),
                doc.inStock());
    }
}
