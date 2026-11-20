package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.repository.CategoryRepository;
import com.ecommerce.search.repository.ProductSearchRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;

/**
 * {@code WF-SEARCH-004} — the filters available on a category landing page.
 *
 * <p>The aggregation uses the same default visibility filters as the search endpoint so
 * the two sets of counts agree within the error of the approximate {@code cardinality}.
 * It deliberately takes no buyer-selected filter: this is the facet bootstrap for a page,
 * not a refinement of an existing result set.
 */
@Service
public class CategoryFacetService {

    private final ProductSearchRepository repository;
    private final CategoryRepository categoryRepository;
    private final ProductQueryBuilder queryBuilder;
    private final FacetAssembler facetAssembler;
    private final FacetLabelResolver facetLabels;
    private final StorefrontFacetPolicy facetPolicy;

    public CategoryFacetService(ProductSearchRepository repository,
                                CategoryRepository categoryRepository,
                                ProductQueryBuilder queryBuilder,
                                FacetAssembler facetAssembler,
                                FacetLabelResolver facetLabels, StorefrontFacetPolicy facetPolicy) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.queryBuilder = queryBuilder;
        this.facetAssembler = facetAssembler;
        this.facetLabels = facetLabels;
        this.facetPolicy = facetPolicy;
    }

    public SearchDtos.FacetSet facetsOf(String categoryId) throws IOException {
        categoryRepository.findById(categoryId)
                .orElseThrow(() -> SearchException.categoryNotFound(categoryId));

        Query query = Query.of(q -> q.bool(bool -> {
            queryBuilder.visibilityFilters(true).forEach(bool::filter);
            bool.filter(filter -> filter.term(term -> term
                    .field("category_ancestor_ids").value(categoryId)));
            return bool;
        }));

        var response = repository.aggregate(query, queryBuilder.facetAggregations(), "category_facets");
        return facetPolicy.apply(facetAssembler.assemble(response.aggregations().get(ProductQueryBuilder.FACET_AGG),
                facetLabels.labels(Optional.of(categoryId))), Optional.of(categoryId));
    }
}
