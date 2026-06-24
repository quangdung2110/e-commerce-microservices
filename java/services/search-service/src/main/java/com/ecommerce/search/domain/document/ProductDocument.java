package com.ecommerce.search.domain.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * Elasticsearch document mapping 1:1 with {@code products_v1} index.
 *
 * <p>Each document represents a single SKU (SKU-first strategy). Products with
 * multiple SKUs are grouped at query time via Field Collapsing on {@code product_id}.
 *
 * <p>Fields use {@link Long}/{@link Boolean} (boxed) for nullable JSON fields.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductDocument(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("product_id") String productId,
    @JsonProperty("category_id") String categoryId,
    @JsonProperty("category_name") String categoryName,
    @JsonProperty("category_path") String categoryPath,
    String name,
    String description,
    @JsonProperty("thumbnail_url") String thumbnailUrl,
    @JsonProperty("product_status") String productStatus,
    @JsonProperty("sku_status") String skuStatus,
    @JsonProperty("searchable_attributes") List<String> searchableAttributes,
    @JsonProperty("variant_attributes") List<String> variantAttributes,
    @JsonProperty("original_price") Long originalPrice,
    @JsonProperty("promotional_price") Long promotionalPrice,
    @JsonProperty("best_price") Long bestPrice,
    @JsonProperty("is_in_stock") Boolean isInStock,
    @JsonProperty("has_promotion") Boolean hasPromotion,
    @JsonProperty("is_deleted") Boolean isDeleted,
    @JsonProperty("deleted_at") Instant deletedAt,
    @JsonProperty("product_rating_avg") Float productRatingAvg,
    @JsonProperty("product_rating_count") Integer productRatingCount,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonProperty("indexed_at") Instant indexedAt,
    @JsonProperty("last_event_id") String lastEventId,
    Instant version
) {
    /**
     * Compute {@code is_in_stock} from current document state.
     *
     * <p>A document is considered in stock when:
     * <ul>
     *   <li>Not soft-deleted ({@code isDeleted == false})</li>
     *   <li>Product status is {@code ACTIVE}</li>
     *   <li>SKU status is {@code ACTIVE}</li>
     * </ul>
     */
    public static boolean computeIsInStock(boolean isDeleted, String productStatus, String skuStatus) {
        return !isDeleted
            && "ACTIVE".equals(productStatus)
            && "ACTIVE".equals(skuStatus);
    }
}
