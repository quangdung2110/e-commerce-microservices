package com.ecommerce.promotion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Promotion Service Application Entry Point.
 * Theo thiết kế technology_choice.md - Spring Boot 3.x + JDK 21.
 */
@SpringBootApplication
@EnableScheduling
public class PromotionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PromotionServiceApplication.class, args);
    }
}
