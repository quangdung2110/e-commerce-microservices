package com.platform.promotionservice.infrastructure.product;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Khi {@code promotion.product.validation-enabled=false} (test/local không có
 * Product): bỏ qua synchronous validation, chỉ còn tombstone watermark guard.
 */
@Component
@ConditionalOnProperty(prefix = "promotion.product", name = "validation-enabled", havingValue = "false")
public class NoopProductCatalogClient implements ProductCatalogClient {

    @Override
    public boolean exists(UUID productId) {
        return true;
    }
}
