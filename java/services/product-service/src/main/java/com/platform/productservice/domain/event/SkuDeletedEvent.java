package com.platform.productservice.domain.event;

/**
 * Domain event fired when a SKU is deleted.
 */
public class SkuDeletedEvent extends DomainEvent {

    private final String productId;
    private final String productName;
    private final String categoryId;
    private final String categoryName;
    private final String skuId;
    private final String skuCode;
    private final String updatedAt;
    private final String deletedAt;

    public SkuDeletedEvent(String productId, String productName, String categoryId,
                           String categoryName, String skuId, String skuCode,
                           String updatedAt, String deletedAt) {
        super(
                "SKU_DELETED",
                "Sku",
                skuId,
                skuId
        );
        this.productId = productId;
        this.productName = productName;
        this.categoryId = categoryId;
        this.categoryName = categoryName;
        this.skuId = skuId;
        this.skuCode = skuCode;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
    }

    public String getProductId() { return productId; }
    public String getProductName() { return productName; }
    public String getCategoryId() { return categoryId; }
    public String getCategoryName() { return categoryName; }
    public String getSkuId() { return skuId; }
    public String getSkuCode() { return skuCode; }
    public String getUpdatedAt() { return updatedAt; }
    public String getDeletedAt() { return deletedAt; }
}
