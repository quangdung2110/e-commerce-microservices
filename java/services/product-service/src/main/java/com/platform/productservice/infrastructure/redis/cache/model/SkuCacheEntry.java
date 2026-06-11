package com.platform.productservice.infrastructure.redis.cache.model;

import com.fasterxml.jackson.annotation.JsonNaming;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.entity.Sku;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Cache entry for SKU data stored in Redis.
 * Contains flattened product + SKU data optimized for point-read queries.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SkuCacheEntry {

    private String skuId;
    private String productId;
    private String skuCode;
    private String productName;
    private String mainImageUrl;
    private BigDecimal originalPrice;
    private BigDecimal promotionalPrice;
    private BigDecimal bestPrice;
    private boolean hasPromotion;
    private String skuStatus;
    private String productStatus;
    private boolean isAvailable;
    private boolean isDeleted;
    private Map<String, String> variantAttributes;
    private int displayQuantity;
    private long lastInventorySyncAt;
    private String priceUpdatedAt;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /**
     * Constructs a cache entry from domain objects.
     */
    public static SkuCacheEntry fromDomain(Product product, Sku sku) {
        SkuCacheEntryBuilder builder = SkuCacheEntry.builder()
                .skuId(sku.getId().getValue())
                .productId(product.getId().getValue())
                .skuCode(sku.getSkuCode())
                .productName(product.getName())
                .originalPrice(sku.getOriginalPrice())
                .promotionalPrice(sku.getPromotionalPrice())
                .bestPrice(sku.getBestPrice())
                .hasPromotion(sku.hasPromotion())
                .skuStatus(sku.getStatus().name())
                .productStatus(product.getStatus().name())
                .isAvailable(sku.isAvailable())
                .isDeleted(sku.isDeleted() || product.isDeleted())
                .displayQuantity(sku.getDisplayQuantity())
                .lastInventorySyncAt(sku.getLastInventorySyncAt())
                .priceUpdatedAt(Instant.ofEpochMilli(sku.getLastInventorySyncAt())
                        .atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        if (sku.getVariantAttributes() != null) {
            Map<String, String> attrs = new HashMap<>();
            for (Map.Entry<String, Object> entry : sku.getVariantAttributes().entrySet()) {
                attrs.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            builder.variantAttributes(attrs);
        }

        if (product.getMedia() != null && !product.getMedia().isEmpty()) {
            builder.mainImageUrl(product.getMedia().get(0).getUrl());
        }

        return builder.build();
    }

    /**
     * Serializes this entry to a JSON string.
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize SkuCacheEntry to JSON", e);
        }
    }

    /**
     * Deserializes a JSON string to a SkuCacheEntry.
     */
    public static SkuCacheEntry fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, SkuCacheEntry.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize SkuCacheEntry from JSON", e);
        }
    }

    /**
     * Applies an inventory update, only if the event timestamp is newer than the last sync.
     *
     * @param displayQuantity  new display quantity
     * @param eventTimestamp   timestamp of the inventory event (epoch millis)
     * @param skuStatus        new SKU status
     * @return true if the update was applied, false if stale
     */
    public boolean mergeInventoryUpdate(int displayQuantity, long eventTimestamp, String skuStatus) {
        if (eventTimestamp <= this.lastInventorySyncAt) {
            return false;
        }
        this.displayQuantity = displayQuantity;
        this.lastInventorySyncAt = eventTimestamp;
        this.skuStatus = skuStatus;
        this.isAvailable = displayQuantity > 0 && "ACTIVE".equals(skuStatus) && !isDeleted;
        return true;
    }
}
