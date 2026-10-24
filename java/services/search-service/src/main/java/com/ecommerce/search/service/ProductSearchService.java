package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.model.SearchCursor;
import com.ecommerce.search.domain.model.ProductMatch;
import com.ecommerce.search.domain.model.SearchQuery;
import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.repository.CategoryRepository;
import com.ecommerce.search.repository.ProductCardRepository;
import com.ecommerce.search.repository.ProductSearchRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * {@code WF-SEARCH-001} — the core storefront query.
 *
 * <p>Snapshot read: filter SKUs, group products, select cheapest eligible SKU, sort and page. No writes,
 * no application cache of results and no internal retry; the client decides whether to try again. The one
 * exception is the 60-second cache of facet group labels in {@link FacetLabelResolver}, which never
 * changes which products or counts are returned.
 *
 * <p>Two implementations of the same contract: the {@code product_cards} projection (ADR-027), where
 * Elasticsearch groups, sorts, pages and counts one page per request, and the SKU grouping of ADR-020,
 * which scans every matching Product. A new search uses the projection when {@link ProductCardReadiness}
 * says it mirrors the current SKU index; otherwise it returns a retryable 503. Only an
 * already-issued legacy cursor may group SKUs; cursors continue on the path that issued them.
 */
@Service
public class ProductSearchService {

    private final ProductSearchRepository repository;
    private final ProductCardRepository cardRepository;
    private final CategoryRepository categoryRepository;
    private final ProductQueryBuilder queryBuilder;
    private final ProductCardQueryBuilder cardQueries;
    private final ProductCardReadiness cardReadiness;
    private final FacetAssembler facetAssembler;
    private final FacetLabelResolver facetLabels;
    private final ThumbnailUrlResolver thumbnails;
    private final SearchCursor cursors;
    private final StorefrontFacetPolicy facetPolicy;

    public ProductSearchService(ProductSearchRepository repository,
                                ProductCardRepository cardRepository,
                                CategoryRepository categoryRepository,
                                ProductQueryBuilder queryBuilder,
                                ProductCardQueryBuilder cardQueries,
                                ProductCardReadiness cardReadiness,
                                FacetAssembler facetAssembler,
                                FacetLabelResolver facetLabels,
                                ThumbnailUrlResolver thumbnails,
                                SearchCursor cursors, StorefrontFacetPolicy facetPolicy) {
        this.repository = repository;
        this.cardRepository = cardRepository;
        this.categoryRepository = categoryRepository;
        this.queryBuilder = queryBuilder;
        this.cardQueries = cardQueries;
        this.cardReadiness = cardReadiness;
        this.facetAssembler = facetAssembler;
        this.facetLabels = facetLabels;
        this.thumbnails = thumbnails;
        this.cursors = cursors;
        this.facetPolicy = facetPolicy;
    }

    public SearchDtos.SearchPage search(SearchQuery request) throws IOException {
        // Decode before opening a PIT so invalid cursors do not allocate ES resources.
        SearchCursor.Position position = request.cursor().map(c -> cursors.decode(request, c)).orElse(null);
        Optional<String> categoryId = resolveCategory(request.category());
        if (position != null && !java.util.Objects.equals(position.categoryId(), categoryId.orElse(null))) {
            throw SearchException.invalidCursor("category changed since this snapshot; restart pagination");
        }
        // Only already-issued v2 cursors may use grouping during migration. A temporary
        // projection outage must not multiply ES load by scanning the whole catalog.
        if (position != null && !position.productCards()) {
            return groupSkus(request, categoryId, position);
        }
        if (position == null && !cardReadiness.ready()) {
            throw new SearchException(ErrorCode.ELASTICSEARCH_UNAVAILABLE,
                    "The product search projection is not ready; retry shortly");
        }
        return searchProductCards(request, categoryId, position);
    }

    /** ADR-027: one native Elasticsearch page over the Product-level projection. */
    private SearchDtos.SearchPage searchProductCards(SearchQuery request, Optional<String> categoryId,
                                                     SearchCursor.Position position) throws IOException {
        var skuQuery = cardQueries.skuQuery(request, categoryId);
        String pit = position == null ? cardRepository.openSnapshot() : position.pitId();
        List<FieldValue> after = position == null ? null : position.after().stream().map(ProductSearchService::fieldValue).toList();
        ProductCardRepository.Page page;
        try {
            page = cardRepository.search(cardQueries.query(skuQuery), cardQueries.sort(request, skuQuery),
                    cardQueries.facets(skuQuery), request.size(), pit, after);
        } catch (IOException | RuntimeException e) {
            cardRepository.closeSnapshot(pit);
            throw e;
        }
        int served = (position == null ? 0 : position.offset()) + page.cards().size();
        String next = null;
        if (!page.cards().isEmpty() && served < page.totalProducts()) {
            List<Object> lastSort = page.cards().get(page.cards().size() - 1).sort().stream()
                    .map(ProductSearchService::wireValue).toList();
            next = cursors.encode(request, new SearchCursor.Position(page.pitId(), served, categoryId.orElse(null), lastSort));
        } else {
            cardRepository.closeSnapshot(page.pitId());
        }
        List<SearchDtos.SearchProductItem> items = page.cards().stream()
                .map(ProductCardRepository.Card::representative).map(this::toItem).toList();
        return new SearchDtos.SearchPage(items, page.totalProducts(), next,
                facetPolicy.apply(facetAssembler.assembleCounts(ProductCardQueryBuilder.facetCounts(page.aggregations()),
                        facetLabels.labels(categoryId)), categoryId));
    }

    /** ADR-020: scan every matching Product's SKUs, then sort and cut the page in Search. */
    private SearchDtos.SearchPage groupSkus(SearchQuery request, Optional<String> categoryId,
                                           SearchCursor.Position position) throws IOException {
        var query = queryBuilder.build(request, categoryId);
        String pit = position == null ? repository.openSnapshot() : position.pitId();
        var snapshot = repository.products(query, pit);
        boolean keepSnapshot = false;
        try {
            List<ProductMatch> products = snapshot.products().stream().sorted(ProductMatch.ordering(request.sort())).toList();
            int offset = position == null ? 0 : position.offset();
            if (offset > products.size()) throw SearchException.invalidCursor("cursor is outside the product result set");
            int end = Math.min(products.size(), offset + request.size());
            List<SearchDtos.SearchProductItem> items = products.subList(offset, end).stream()
                    .map(ProductMatch::representative).map(this::toItem).toList();
            String next = end < products.size()
                    ? cursors.encode(request, new SearchCursor.Position(snapshot.pitId(), end, categoryId.orElse(null))) : null;
            var page = new SearchDtos.SearchPage(items, products.size(), next,
                    facetPolicy.apply(facetAssembler.assemble(snapshot.facets().get(ProductQueryBuilder.FACET_AGG),
                            facetLabels.labels(categoryId)), categoryId));
            keepSnapshot = next != null;
            return page;
        } finally {
            if (!keepSnapshot) repository.closeSnapshot(snapshot.pitId());
        }
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

    /** A {@code search_after} sort value as a plain JSON value for the signed cursor. */
    static Object wireValue(FieldValue value) {
        if (value.isLong()) return value.longValue();
        if (value.isDouble()) return value.doubleValue();
        if (value.isBoolean()) return value.booleanValue();
        if (value.isString()) return value.stringValue();
        return null;
    }

    /** The inverse of {@link #wireValue}, from what the cursor's JSON parser produced. */
    static FieldValue fieldValue(Object value) {
        if (value == null) return FieldValue.NULL;
        if (value instanceof Integer || value instanceof Long) return FieldValue.of(((Number) value).longValue());
        if (value instanceof Number number) return FieldValue.of(number.doubleValue());
        if (value instanceof Boolean flag) return FieldValue.of(flag);
        return FieldValue.of(value.toString());
    }
}
