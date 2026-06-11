package com.platform.productservice.domain.event;

import com.platform.productservice.domain.entity.Sku;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Domain event fired when a SKU is updated.
 * Partition key is sku_id (NOT product_id).
 */
public class SkuUpdatedEvent extends DomainEvent {

    private final String productId;
    private final String skuId;
    private final String skuCode;
    private final String productName;
    private final String categoryId;
    private final BigDecimal originalPrice;
    private final BigDecimal promotionalPrice;
    private final BigDecimal bestPrice;
    private final boolean hasPromotion;
    private final String skuStatus;
    private final String productStatus;
    private final boolean isAvailable;
    private final int displayQuantity;
    private final Map<String, Object> variantAttributes;
    private final List<String> changedFields;
    private final String changeSource;
    private final String updatedAt;

    public SkuUpdatedEvent(Sku sku, String productName, String categoryId,
                           String productStatus, List<String> changedFields, String changeSource) {
        super(
                "SKU_UPDATED",
                "Sku",
                sku.getId().getValue(),
                sku.getId().getValue()
        );
        this.productId = sku.getProductId().getValue();
        this.skuId = sku.getId().getValue();
        this.skuCode = sku.getSkuCode();
        this.productName = productName;
        this.categoryId = categoryId;
        this.originalPrice = sku.getOriginalPrice();
        this.promotionalPrice = sku.getPromotionalPrice();
        this.bestPrice = sku.getBestPrice();
        this.hasPromotion = sku.hasPromotion();
        this.skuStatus = sku.getStatus().name();
        this.productStatus = productStatus;
        this.isAvailable = sku.isAvailable();
        this.displayQuantity = sku.getDisplayQuantity();
        this.variantAttributes = sku.getVariantAttributes();
        this.changedFields = changedFields;
        this.changeSource = changeSource;
        this.updatedAt = sku.getUpdatedAt() != null ? sku.getUpdatedAt().toString() : null;
    }

    public String getProductId() { return productId; }
    public String getSkuId() { return skuId; }
    public String getSkuCode() { return skuCode; }
    public String getProductName() { return productName; }
    public String getCategoryId() { return categoryId; }
    public BigDecimal getOriginalPrice() { return originalPrice; }
    public BigDecimal getPromotionalPrice() { return promotionalPrice; }
    public BigDecimal getBestPrice() { return bestPrice; }
    public boolean isHasPromotion() { return hasPromotion; }
    public String getSkuStatus() { return skuStatus; }
    public String getProductStatus() { return productStatus; }
    public boolean isAvailable() { return isAvailable; }
    public int getDisplayQuantity() { return displayQuantity; }
    public Map<String, Object> getVariantAttributes() { return variantAttributes; }
    public List<String> getChangedFields() { return changedFields; }
    public String getChangeSource() { return changeSource; }
    public String getUpdatedAt() { return updatedAt; }
}
