package com.platform.productservice.domain.entity;

import com.platform.productservice.domain.enums.SkuStatus;
import com.platform.productservice.domain.exception.DomainException;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.SkuId;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SKU entity managed by the Product aggregate.
 * Represents a specific product variant with pricing and inventory.
 */
public class Sku {

    private SkuId id;
    private ProductId productId;
    private String skuCode;
    private BigDecimal originalPrice;
    private BigDecimal promotionalPrice;
    private SkuStatus status;
    private boolean isDeleted;
    private LocalDateTime deletedAt;
    private Integer displayQuantity;
    private long lastInventorySyncAt;
    private java.util.Map<String, Object> variantAttributes;
    private java.util.List<ProductMedia> media;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Sku() {
        this.variantAttributes = new java.util.HashMap<>();
        this.media = new ArrayList<>();
    }

    public void initialize(ProductId productId) {
        this.id = SkuId.generate();
        this.productId = productId;
        this.status = SkuStatus.ACTIVE;
        this.displayQuantity = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public BigDecimal getBestPrice() {
        return promotionalPrice != null ? promotionalPrice : originalPrice;
    }

    public boolean hasPromotion() {
        return promotionalPrice != null;
    }

    public boolean isInStock() {
        return displayQuantity > 0 && status == SkuStatus.ACTIVE && !isDeleted;
    }

    public boolean isAvailable() {
        return isInStock() && !isDeleted;
    }

    public void applyMarkdown(BigDecimal calculatedPrice, String ruleId) {
        if (calculatedPrice == null || calculatedPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Calculated price must be non-negative");
        }
        this.promotionalPrice = calculatedPrice;
        this.updatedAt = LocalDateTime.now();
    }

    public void removeMarkdown() {
        this.promotionalPrice = null;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateDisplayQuantity(int quantity, long syncTimestamp) {
        if (syncTimestamp > this.lastInventorySyncAt) {
            this.displayQuantity = quantity;
            this.lastInventorySyncAt = syncTimestamp;
            this.updatedAt = LocalDateTime.now();
        }
    }

    public void markDeleted() {
        this.isDeleted = true;
        this.deletedAt = LocalDateTime.now();
    }

    public SkuId getId() { return id; }
    public ProductId getProductId() { return productId; }
    public String getSkuCode() { return skuCode; }
    public BigDecimal getOriginalPrice() { return originalPrice; }
    public BigDecimal getPromotionalPrice() { return promotionalPrice; }
    public SkuStatus getStatus() { return status; }
    public boolean isDeleted() { return isDeleted; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public Integer getDisplayQuantity() { return displayQuantity; }
    public long getLastInventorySyncAt() { return lastInventorySyncAt; }
    public java.util.Map<String, Object> getVariantAttributes() { return variantAttributes; }
    public java.util.List<ProductMedia> getMedia() { return media; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(SkuId id) { this.id = id; }
    public void setProductId(ProductId productId) { this.productId = productId; }
    public void setSkuCode(String skuCode) { this.skuCode = skuCode; }
    public void setOriginalPrice(BigDecimal originalPrice) { this.originalPrice = originalPrice; }
    public void setPromotionalPrice(BigDecimal promotionalPrice) { this.promotionalPrice = promotionalPrice; }
    public void setStatus(SkuStatus status) { this.status = status; }
    public void setDeleted(boolean deleted) { isDeleted = deleted; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public void setDisplayQuantity(Integer displayQuantity) { this.displayQuantity = displayQuantity; }
    public void setLastInventorySyncAt(long lastInventorySyncAt) { this.lastInventorySyncAt = lastInventorySyncAt; }
    public void setVariantAttributes(java.util.Map<String, Object> variantAttributes) {
        this.variantAttributes = variantAttributes != null ? variantAttributes : new java.util.HashMap<>();
    }
    public void setMedia(java.util.List<ProductMedia> media) { this.media = media != null ? media : new ArrayList<>(); }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
