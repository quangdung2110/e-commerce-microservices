package com.ecommerce.promotion.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka Topic Configuration.
 * Theo thiết kế promotion_events.md - Section 4: Topic Mapping Table.
 *
 * 3 outbound topics:
 * - t.promotion.markdown.events  → MARKDOWN_* events
 * - t.promotion.coupon.events    → COUPON_* events
 * - t.promotion.budget.events    → BUDGET_* events
 *
 * 1 inbound topic (không tạo ở đây, consumed only):
 * - t.order.events → ORDER_PAID, ORDER_CANCELLED, ORDER_REFUNDED
 */
@Configuration
public class KafkaTopicConfig {

    public static final String PROMOTION_MARKDOWN_EVENTS_TOPIC = "t.promotion.markdown.events";
    public static final String PROMOTION_COUPON_EVENTS_TOPIC = "t.promotion.coupon.events";
    public static final String PROMOTION_BUDGET_EVENTS_TOPIC = "t.promotion.budget.events";

    @Value("${kafka.topic.replicas:1}")
    private int replicas;

    @Bean
    public NewTopic promotionMarkdownEventsTopic() {
        return TopicBuilder.name(PROMOTION_MARKDOWN_EVENTS_TOPIC)
                .partitions(3)
                .replicas(replicas)
                .build();
    }

    @Bean
    public NewTopic promotionCouponEventsTopic() {
        return TopicBuilder.name(PROMOTION_COUPON_EVENTS_TOPIC)
                .partitions(6)  // Higher partitions cho Flash Sale throughput
                .replicas(replicas)
                .build();
    }

    @Bean
    public NewTopic promotionBudgetEventsTopic() {
        return TopicBuilder.name(PROMOTION_BUDGET_EVENTS_TOPIC)
                .partitions(3)
                .replicas(replicas)
                .build();
    }
}
