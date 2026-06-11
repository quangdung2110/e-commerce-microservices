package com.platform.productservice.domain.event;

import java.util.List;

/**
 * Domain event fired when a product status changes.
 */
public class ProductStatusChangedEvent extends DomainEvent {

    private final String productId;
    private final String oldStatus;
    private final String newStatus;
    private final String changedBy;
    private final String changedByType;
    private final String reason;
    private final List<String> skuIds;

    public ProductStatusChangedEvent(String productId, String oldStatus, String newStatus,
                                     String changedBy, String changedByType, String reason,
                                     List<String> skuIds) {
        super(
                "PRODUCT_STATUS_CHANGED",
                "Product",
                productId,
                productId
        );
        this.productId = productId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.changedBy = changedBy;
        this.changedByType = changedByType;
        this.reason = reason;
        this.skuIds = skuIds;
    }

    public String getProductId() { return productId; }
    public String getOldStatus() { return oldStatus; }
    public String getNewStatus() { return newStatus; }
    public String getChangedBy() { return changedBy; }
    public String getChangedByType() { return changedByType; }
    public String getReason() { return reason; }
    public List<String> getSkuIds() { return skuIds; }
}
