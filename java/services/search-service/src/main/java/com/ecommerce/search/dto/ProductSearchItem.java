package com.ecommerce.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * A single search result item representing the best-matching SKU of a product,
 * optionally accompanied by additional matching SKUs via {@code matchingSkus}
 * (populated from Elasticsearch Field Collapsing {@code inner_hits}).
 */
@Builder
public record ProductSearchItem(
    @JsonProperty("product_id") String productId,
    @JsonProperty("sku_id") String skuId,
    String name,
    @JsonProperty("thumbnail_url") String thumbnailUrl,
    @JsonProperty("original_price") Long originalPrice,
    @JsonProperty("best_price") Long bestPrice,
    @JsonProperty("discount_percentage") Integer discountPercentage,
    @JsonProperty("product_rating_avg") Float productRatingAvg,
    @JsonProperty("is_in_stock") Boolean isInStock,
    @JsonProperty("matching_skus") List<MatchingSku> matchingSkus
) {
    @Builder
    public record MatchingSku(
        @JsonProperty("sku_id") String skuId,
        @JsonProperty("best_price") Long bestPrice,
        @JsonProperty("variant_attributes") List<String> variantAttributes
    ) {}
}
