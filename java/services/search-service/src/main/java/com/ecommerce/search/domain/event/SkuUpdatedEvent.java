package com.ecommerce.search.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Payload for {@code SKU_UPDATED} events consumed from {@code t.product.events}.
 *
 * <p>This event type also carries product-status changes through the
 * {@code productStatusChanged} flag (Phase 19). When {@code productStatusChanged}
 * is {@code true}, the worker additionally issues an {@code _update_by_query}
 * across all SKU documents of the product to synchronise {@code product_status}
 * and recompute {@code is_in_stock}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkuUpdatedEvent(
    @JsonProperty("product_id") String productId,
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("sku_code") String skuCode,
    @JsonProperty("category_id") String categoryId,
    @JsonProperty("original_price") Long originalPrice,
    @JsonProperty("promotional_price") Long promotionalPrice,
    @JsonProperty("best_price") Long bestPrice,
    @JsonProperty("has_promotion") Boolean hasPromotion,
    @JsonProperty("sku_status") String skuStatus,
    @JsonProperty("variant_attributes_json") Map<String, Object> variantAttributesJson,
    @JsonProperty("product_status_changed") Boolean productStatusChanged,
    @JsonProperty("new_product_status") String newProductStatus,
    @JsonProperty("change_source") String changeSource,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonProperty("changed_fields") List<String> changedFields
) {}
