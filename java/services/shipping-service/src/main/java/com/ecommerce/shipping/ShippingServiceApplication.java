package com.ecommerce.shipping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

// Theo thiết kế technology_choice.md mục 4: Spring Boot 3.x + Java 21.
@SpringBootApplication
@EnableScheduling
@EnableCaching
public class ShippingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShippingServiceApplication.class, args);
    }
}
