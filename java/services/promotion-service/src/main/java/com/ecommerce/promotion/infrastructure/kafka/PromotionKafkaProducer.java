package com.ecommerce.promotion.infrastructure.kafka;

import com.ecommerce.promotion.infrastructure.config.KafkaTopicConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Kafka Producer — Direct event publishing (ngoài Outbox pattern).
 * Theo thiết kế promotion_outbox_pattern.md - Section 6:
 *   Flash Sale COUPON_CLAIMED → Kafka TRỰC TIẾP (KHÔNG qua Outbox)
 *   vì không ghi DB trước tại thời điểm claim.
 *
 * Outbox-based events được publish bởi OutboxRelayWorker, KHÔNG qua class này.
 */
@Component
public class PromotionKafkaProducer {

    private static final Logger log = LoggerFactory.getLogger(PromotionKafkaProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PromotionKafkaProducer(KafkaTemplate<String, String> kafkaTemplate,
                                  ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Publish COUPON_CLAIMED event trực tiếp lên Kafka.
     * Theo thiết kế promotion_events.md - Section 2.4: COUPON_CLAIMED
     * Theo thiết kế promotion_outbox_pattern.md - Section 6: Direct Kafka (không Outbox).
     *
     * Topic: t.promotion.coupon.events
     * Partition Key: promotion_id (để mọi claims cùng promotion nằm cùng partition)
     *
     * @param promotionId ID promotion
     * @param claimId     ID claim (từ Redis SPOP)
     * @param userId      ID user
     */
    public void publishCouponClaimed(Long promotionId, Long claimId, Long userId) {
        Map<String, Object> payload = Map.of(
                "promotion_id", promotionId,
                "claim_id", claimId,
                "user_id", userId,
                "claimed_at", System.currentTimeMillis()
        );

        try {
            String payloadJson = objectMapper.writeValueAsString(payload);

            Message<String> message = MessageBuilder.withPayload(payloadJson)
                    .setHeader(KafkaHeaders.TOPIC, KafkaTopicConfig.PROMOTION_COUPON_EVENTS_TOPIC)
                    .setHeader(KafkaHeaders.KEY, promotionId.toString())
                    .setHeader("event_type", "COUPON_CLAIMED")
                    .setHeader("event_id", promotionId + "_" + claimId + "_CLAIMED")
                    .build();

            kafkaTemplate.send(message).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish COUPON_CLAIMED: promo={}, claim={}, error={}",
                            promotionId, claimId, ex.getMessage());
                } else {
                    log.info("Published COUPON_CLAIMED: promo={}, claim={}, partition={}, offset={}",
                            promotionId, claimId,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize COUPON_CLAIMED payload: promo={}, claim={}", promotionId, claimId, e);
            throw new RuntimeException("Failed to serialize COUPON_CLAIMED payload", e);
        }
    }
}
