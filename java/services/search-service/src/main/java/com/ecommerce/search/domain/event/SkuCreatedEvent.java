package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Payload for {@code SKU_CREATED} events consumed from {@code t.product.events}.
 *
 * <p>Carries the full definition of a newly created SKU belonging to an existing
 * product. The worker uses {@code created_at} (not {@code updated_at}) as the
 * guard timestamp because the entity did not exist before this event.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkuCreatedEvent(
    @JsonProperty("product_id") String productId,
    String name,
    @JsonProperty("category_id") String categoryId,
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("sku_code") String skuCode,
    @JsonProperty("original_price") Long originalPrice,
    @JsonProperty("promotional_price") Long promotionalPrice,
    @JsonProperty("best_price") Long bestPrice,
    @JsonProperty("has_promotion") Boolean hasPromotion,
    @JsonProperty("sku_status") String skuStatus,
    @JsonProperty("product_status") String productStatus,
    @JsonProperty("variant_attributes_json") Map<String, Object> variantAttributesJson,
    List<Map<String, Object>> media,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonProperty("created_at") Instant createdAt
) {}
