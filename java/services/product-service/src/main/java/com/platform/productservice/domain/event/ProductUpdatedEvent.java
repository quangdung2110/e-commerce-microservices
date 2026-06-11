package com.platform.productservice.domain.event;

import java.util.List;

/**
 * Domain event fired when a product is updated.
 */
public class ProductUpdatedEvent extends DomainEvent {

    private final String productId;
    private final List<String> changedFields;
    private final List<String> skuIds;
    private final String updatedAt;

    public ProductUpdatedEvent(String productId, List<String> changedFields,
                               List<String> skuIds, String updatedAt) {
        super(
                "PRODUCT_UPDATED",
                "Product",
                productId,
                productId
        );
        this.productId = productId;
        this.changedFields = changedFields;
        this.skuIds = skuIds;
        this.updatedAt = updatedAt;
    }

    public String getProductId() { return productId; }
    public List<String> getChangedFields() { return changedFields; }
    public List<String> getSkuIds() { return skuIds; }
    public String getUpdatedAt() { return updatedAt; }
}
