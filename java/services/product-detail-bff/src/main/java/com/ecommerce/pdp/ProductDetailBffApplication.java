package com.ecommerce.pdp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Product Detail BFF: read composition của PDP (docs/product-detail-bff). Không có
 * database, Kafka hay cache bền; Inventory và Review được gọi theo từng request, catalog
 * Product được giữ in-process tối đa một TTL ngắn (ADR-011).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProductDetailBffApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductDetailBffApplication.class, args);
    }
}
