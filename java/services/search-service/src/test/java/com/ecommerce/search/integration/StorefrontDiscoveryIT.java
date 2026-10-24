package com.ecommerce.search.integration;

import com.ecommerce.search.dto.SearchDtos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Real ES query checks: policy must affect results/counts before paging, not just hide cards. */
@TestPropertySource(properties = {
        "search.storefront.common-facets=brand,color",
        "search.storefront.category-facets.laptops=ram",
        "search.storefront.excluded-product-ids=fixture-hidden",
        "search.storefront.excluded-category-ids=internal-cat"
})
class StorefrontDiscoveryIT extends AbstractSearchIntegrationTest {
    @Autowired TestRestTemplate rest;

    @Test void discoveryPolicyFiltersResultsCountsFacetsTreeAndPreviouslyBuiltSuggestions() throws Exception {
        category("public-cat", "laptops"); category("internal-cat", "internal");
        sku("sku-public", "public-product", "public-cat");
        sku("sku-qa", "fixture-hidden", "public-cat");
        sku("sku-internal", "another-qa-product", "internal-cat");
        client.index(i -> i.index(properties.aliases().searchSuggestions()).id("public-suggest").document(Map.of(
                "suggestion_id", "public-suggest", "text", "Laptop public", "type", "PRODUCT", "product_id", "public-product", "weight", 1)));
        client.index(i -> i.index(properties.aliases().searchSuggestions()).id("qa-suggest").document(Map.of(
                "suggestion_id", "qa-suggest", "text", "Laptop QA", "type", "PRODUCT", "product_id", "fixture-hidden", "weight", 100)));
        refreshAll(); client.indices().refresh(i -> i.index(properties.aliases().searchSuggestions()));

        var all = rest.getForObject("/api/v2/search/products?in_stock=false&size=1", SearchDtos.SearchPage.class);
        assertThat(all.totalProducts()).isEqualTo(1);
        assertThat(all.nextCursor()).isNull();
        assertThat(all.items()).extracting(SearchDtos.SearchProductItem::productId).containsExactly("public-product");
        assertThat(all.facets().filters()).extracting(SearchDtos.AttributeFacet::code).containsExactly("brand", "color");
        assertThat(all.facets().filters().getFirst().options().getFirst().count()).isEqualTo(1);
        var scoped = rest.getForObject("/api/v2/search/products?category=laptops&in_stock=false", SearchDtos.SearchPage.class);
        assertThat(scoped.facets().filters()).extracting(SearchDtos.AttributeFacet::code).containsExactly("brand", "color", "ram");
        var boot = rest.getForObject("/api/v2/search/categories/public-cat/facets", SearchDtos.FacetSet.class);
        assertThat(boot.filters()).extracting(SearchDtos.AttributeFacet::code).containsExactly("brand", "color", "ram");
        var tree = rest.getForObject("/api/v2/search/categories", SearchDtos.CategoryTree.class);
        assertThat(tree.categories()).extracting(SearchDtos.CategoryNode::categoryId).containsExactly("public-cat");
        var suggestions = rest.getForObject("/api/v2/search/suggestions?q=lap&limit=1", SearchDtos.SuggestionList.class);
        assertThat(suggestions.items()).extracting(SearchDtos.Suggestion::text).containsExactly("Laptop public");
    }

    private void category(String id, String slug) throws Exception {
        client.index(i -> i.index(properties.aliases().categoriesWrite()).id(id).document(Map.of(
                "category_id", id, "name", slug, "slug", slug, "ancestor_ids", List.of(id), "ancestor_slugs", List.of(slug),
                "level", 0, "is_leaf", true, "is_deleted", false)));
    }
    private void sku(String id, String productId, String categoryId) throws Exception {
        Map<String, Object> fields = new HashMap<>();
        fields.put("sku_id", id); fields.put("sku_code", id); fields.put("product_id", productId); fields.put("product_slug", productId);
        fields.put("name", "Laptop"); fields.put("product_status", "ACTIVE"); fields.put("sku_status", "ACTIVE");
        fields.put("is_deleted", false); fields.put("is_in_stock", true); fields.put("base_price_vnd", 100000); fields.put("selling_price_vnd", 100000);
        fields.put("category_id", categoryId); fields.put("category_ancestor_ids", List.of(categoryId));
        fields.put("filter_tokens", List.of("brand:s:nova", "color:s:black", "ram:s:16gb", "cau-hinh:s:legacy"));
        client.index(i -> i.index(properties.aliases().productsWrite()).id(id).document(fields));
    }
}
