package com.ecommerce.search.service;

import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.model.SearchCursor;
import com.ecommerce.search.domain.model.ProductMatch;
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
 * <p>Snapshot read: filter SKUs, group products, select cheapest eligible SKU, sort and page. No writes,
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
        // Decode before opening a PIT so invalid cursors do not allocate ES resources.
        SearchCursor.Position position = request.cursor().map(c -> cursors.decode(request, c)).orElse(null);
        Optional<String> categoryId = resolveCategory(request.category());
        if (position != null && !java.util.Objects.equals(position.categoryId(), categoryId.orElse(null))) {
            throw SearchException.invalidCursor("category changed since this snapshot; restart pagination");
        }
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
                    facetAssembler.assemble(snapshot.facets().get(ProductQueryBuilder.FACET_AGG)));
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
}
