package com.ecommerce.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** Editorial discovery policy. Exclusions are explicit IDs, never name/prefix heuristics. */
@Component
@ConfigurationProperties(prefix = "search.storefront")
public class StorefrontProperties {
    private List<String> commonFacets = List.of("thuong-hieu", "mau-sac");
    private Map<String, List<String>> categoryFacets = Map.of();
    private List<String> excludedProductIds = List.of();
    private List<String> excludedCategoryIds = List.of();
    public List<String> getCommonFacets() { return commonFacets; }
    public void setCommonFacets(List<String> value) { commonFacets = value == null ? List.of() : List.copyOf(value); }
    public Map<String, List<String>> getCategoryFacets() { return categoryFacets; }
    public void setCategoryFacets(Map<String, List<String>> value) { categoryFacets = value == null ? Map.of() : Map.copyOf(value); }
    public List<String> getExcludedProductIds() { return excludedProductIds; }
    public void setExcludedProductIds(List<String> value) { excludedProductIds = value == null ? List.of() : List.copyOf(value); }
    public List<String> getExcludedCategoryIds() { return excludedCategoryIds; }
    public void setExcludedCategoryIds(List<String> value) { excludedCategoryIds = value == null ? List.of() : List.copyOf(value); }
}
