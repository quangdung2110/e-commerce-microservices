package com.platform.productservice.domain.event;

import java.util.List;

/**
 * Domain event fired when a product is deleted.
 */
public class ProductDeletedEvent extends DomainEvent {

    private final String productId;
    private final List<String> skuIds;
    private final String updatedAt;
    private final String deletedAt;

    public ProductDeletedEvent(String productId, List<String> skuIds,
                               String updatedAt, String deletedAt) {
        super(
                "PRODUCT_DELETED",
                "Product",
                productId,
                productId
        );
        this.productId = productId;
        this.skuIds = skuIds;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
    }

    public String getProductId() { return productId; }
    public List<String> getSkuIds() { return skuIds; }
    public String getUpdatedAt() { return updatedAt; }
    public String getDeletedAt() { return deletedAt; }
}
