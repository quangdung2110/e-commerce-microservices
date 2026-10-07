package com.platform.productservice;

import com.platform.productservice.config.ProductProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Product Service: source of truth cho catalog, giá niêm yết và giá bán công bố.
 *
 * <p>Không có Redis/cache, không có inventory projection hay worker, không có
 * scheduled job. Workload nền duy nhất là markdown consumer
 * (docs/product/README.md, ADR-011).
 */
@SpringBootApplication
@EnableConfigurationProperties(ProductProperties.class)
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
