package com.ecommerce.search.domain.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * A SKU document in the {@code products} index, {@code _id = sku_id}
 * ({@code SEARCH-INV-002}).
 *
 * <p>Read model only: the write path never serialises this record, it sends the canonical
 * painless scripts instead, so that each source can only touch the fields it owns
 * ({@code SEARCH-BR-009}).
 *
 * <p>{@code isInStock} is nullable by design. A SKU created by a catalog event has no
 * availability until Inventory publishes its first snapshot, and
 * {@code catalog_upsert.painless} deliberately does not initialise it; an absent value is
 * read as {@code false} rather than written into {@code _source} as a guess
 * ({@code docs/search/data.md §is_in_stock}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductDocument(
        String skuId,
        String skuCode,
        String productId,
        String productSlug,

        String categoryId,
        String categorySlug,
        String categoryName,
        List<String> categoryAncestorIds,
        List<String> categorySlugPath,

        String name,
        String description,
        String thumbnailStorageKey,

        String productStatus,
        String skuStatus,
        List<String> productAttributeTokens,
        List<String> variantAttributeTokens,
        List<String> filterTokens,

        Long basePriceVnd,
        Long sellingPriceVnd,
        Boolean hasPromotion,

        Boolean isInStock,

        Double productRatingAvg,
        Long productRatingCount,

        Boolean isDeleted,
        Instant deletedAt,
        Instant updatedAt,
        Instant catalogIndexedAt,
        Instant availabilityObservedAt,
        Instant availabilityIndexedAt,
        Instant reviewIndexedAt,
        Instant indexedAt,

        Long catalogRevision,
        Long availabilityRevision,
        Long reviewRevision,

        String lastCatalogEventId,
        String lastAvailabilityEventId,
        String lastReviewEventId) {

    public boolean inStock() {
        return Boolean.TRUE.equals(isInStock);
    }
}
