package com.ecommerce.review;

import com.ecommerce.review.config.ReviewProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Review Service.
 *
 * <p>Owns verified-purchase entitlements, the public review lifecycle, media asset
 * metadata, moderation, legal takedowns, privacy erasure and the product rating
 * aggregate. Every outbound business event leaves through the PostgreSQL outbox and
 * Debezium; there is no Kafka producer in business code (ADR-003).
 */
@SpringBootApplication
@EnableConfigurationProperties(ReviewProperties.class)
@EnableScheduling
public class ReviewServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewServiceApplication.class, args);
    }
}
