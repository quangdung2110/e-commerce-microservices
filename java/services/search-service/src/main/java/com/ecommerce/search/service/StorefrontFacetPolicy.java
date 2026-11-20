package com.ecommerce.search.service;

import com.ecommerce.search.config.StorefrontProperties;
import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.repository.CategoryRepository;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Picks useful facet groups without changing tokens, eligibility or counts. */
@Component
public class StorefrontFacetPolicy {
    private final StorefrontProperties properties;
    private final CategoryRepository categories;
    public StorefrontFacetPolicy(StorefrontProperties properties, CategoryRepository categories) {
        this.properties = properties;
        this.categories = categories;
    }
    public SearchDtos.FacetSet apply(SearchDtos.FacetSet source, Optional<String> categoryId) {
        List<String> ordered = new ArrayList<>(properties.getCommonFacets());
        try { if (categoryId.isPresent()) {
            var category = categories.findById(categoryId.get());
            if (category.isPresent()) {
                // A more specific category overrides the policy inherited from its ancestors.
                List<String> slugs = new ArrayList<>(Optional.ofNullable(category.get().ancestorSlugs()).orElse(List.of()));
                slugs.add(category.get().slug());
                List<String> specific = List.of();
                for (String slug : slugs) {
                    if (properties.getCategoryFacets().containsKey(slug)) specific = properties.getCategoryFacets().get(slug);
                }
                ordered.addAll(specific);
            }
        } } catch (IOException unavailable) {
            org.slf4j.LoggerFactory.getLogger(StorefrontFacetPolicy.class).warn("cannot resolve storefront facet policy; using common facets", unavailable);
        }
        return SearchDtos.FacetSet.of(ordered.stream().distinct()
                .flatMap(code -> source.filters().stream().filter(facet -> facet.code().equals(code)))
                .toList());
    }
}
