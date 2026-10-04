package com.ecommerce.inventory;

import com.ecommerce.inventory.config.InventoryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Inventory Service: sole owner of warehouse stock, reservations, returns and the per-SKU
 * availability projection (docs/inventory/README.md).
 */
@SpringBootApplication
@EnableConfigurationProperties(InventoryProperties.class)
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
