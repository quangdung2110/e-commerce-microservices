package com.platform.promotionservice;

import com.platform.promotionservice.config.PromotionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Promotion Service: campaign, PLATFORM_VOUCHER toàn đơn và product markdown
 * (docs/promotion/README.md). PostgreSQL là source of truth; Redis là runtime
 * projection; Kafka chỉ qua transactional outbox + Debezium (ADR-016).
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PromotionProperties.class)
public class PromotionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PromotionServiceApplication.class, args);
    }
}
