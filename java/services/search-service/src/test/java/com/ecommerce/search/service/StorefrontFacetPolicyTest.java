package com.ecommerce.search.service;

import com.ecommerce.search.config.StorefrontProperties;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StorefrontFacetPolicyTest {
    private final StorefrontProperties settings = new StorefrontProperties();
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final StorefrontFacetPolicy policy = new StorefrontFacetPolicy(settings, categories);
    private final SearchDtos.FacetOption option = new SearchDtos.FacetOption("ram:s:16gb", "16GB", 3);
    private final SearchDtos.FacetSet source = SearchDtos.FacetSet.of(List.of(
            facet("cau-hinh"), facet("ram"), facet("qa-axis"), facet("mau-sac"), facet("thuong-hieu")));
    private SearchDtos.AttributeFacet facet(String code) { return SearchDtos.AttributeFacet.multiSelect(code, code, List.of(option)); }

    @Test void unscopedSearchAdvertisesOnlyCommonFacetsInEditorialOrder() throws Exception {
        var result = policy.apply(source, Optional.empty());
        assertThat(result.filters()).extracting(SearchDtos.AttributeFacet::code).containsExactly("thuong-hieu", "mau-sac");
        assertThat(result.filters().getFirst().options()).containsExactly(option);
        verifyNoInteractions(categories);
    }
    @Test void categoryPolicyInheritsThenAllowsTheMostSpecificOverrideWithoutChangingTokensOrCounts() throws Exception {
        settings.setCategoryFacets(Map.of("laptop", List.of("ram"), "gaming-laptop", List.of("ram", "mau-sac")));
        var category = mock(CategoryDocument.class);
        when(category.ancestorSlugs()).thenReturn(List.of("laptop"));
        when(category.slug()).thenReturn("gaming-laptop");
        when(categories.findById("leaf")).thenReturn(Optional.of(category));
        var result = policy.apply(source, Optional.of("leaf"));
        assertThat(result.filters()).extracting(SearchDtos.AttributeFacet::code).containsExactly("thuong-hieu", "mau-sac", "ram");
        assertThat(result.filters().getLast().options().getFirst()).isEqualTo(option);
    }
}
