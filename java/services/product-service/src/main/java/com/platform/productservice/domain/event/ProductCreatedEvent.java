package com.platform.productservice.domain.event;

import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.vo.CategoryId;
import com.platform.productservice.domain.vo.ProductId;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Domain event fired when a product is created.
 */
public class ProductCreatedEvent extends DomainEvent {

    private final String productId;
    private final String name;
    private final String categoryId;
    private final String status;
    private final List<String> skuIds;

    public ProductCreatedEvent(Product product) {
        super(
                "PRODUCT_CREATED",
                "Product",
                product.getId().getValue(),
                product.getId().getValue()
        );
        this.productId = product.getId().getValue();
        this.name = product.getName();
        this.categoryId = product.getCategoryId() != null ? product.getCategoryId().getValue() : null;
        this.status = product.getStatus().name();
        this.skuIds = product.getSkus() != null
                ? product.getSkus().stream()
                    .map(sku -> sku.getId().getValue())
                    .collect(Collectors.toList())
                : List.of();
    }

    public String getProductId() { return productId; }
    public String getName() { return name; }
    public String getCategoryId() { return categoryId; }
    public String getStatus() { return status; }
    public List<String> getSkuIds() { return skuIds; }
}
