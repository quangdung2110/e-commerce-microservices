package com.ecommerce.search.domain.model;

import com.ecommerce.search.domain.document.ProductDocument;
import java.util.Comparator;

/** One matching product, with its cheapest eligible SKU and product-level sort metrics. */
public record ProductMatch(ProductDocument representative, double relevance, double newest, double rating) {
    public static Comparator<ProductMatch> ordering(SortOption sort) {
        Comparator<ProductMatch> primary = switch (sort) {
            case PRICE_ASC -> Comparator.comparingLong(p -> p.representative().sellingPriceVnd());
            case PRICE_DESC -> Comparator.<ProductMatch>comparingLong(p -> p.representative().sellingPriceVnd()).reversed();
            case RELEVANCE -> Comparator.comparingDouble(ProductMatch::relevance).reversed();
            case NEWEST -> Comparator.comparingDouble(ProductMatch::newest).reversed();
            case RATING -> Comparator.comparingDouble(ProductMatch::rating).reversed();
        };
        return primary.thenComparing(p -> p.representative().productId());
    }
}
