package com.ecommerce.checkout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Checkout BFF (docs/checkout): composes Cart, User, Product, Inventory and Promotion into one
 * immutable quotation and submits it to Order exactly once. The only state it owns is the
 * short-lived workflow state in Redis; there is no database, Kafka or background worker.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CheckoutServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CheckoutServiceApplication.class, args);
    }
}
