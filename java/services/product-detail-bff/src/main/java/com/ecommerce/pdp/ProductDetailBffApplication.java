package com.ecommerce.pdp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Product Detail BFF: read composition của PDP (docs/product-detail-bff). Không có
 * database, Kafka hay cache bền; mọi dữ liệu đến từ Product, Inventory và Review theo
 * từng request.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProductDetailBffApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductDetailBffApplication.class, args);
    }
}
