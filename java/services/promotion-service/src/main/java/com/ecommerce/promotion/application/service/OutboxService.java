package com.ecommerce.promotion.application.service;

import com.ecommerce.promotion.domain.entity.PromotionOutbox;
import com.ecommerce.promotion.domain.enums.OutboxStatus;
import com.ecommerce.promotion.domain.repository.PromotionOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Ghi Event vào bảng promotion_outbox trong cùng Transaction.
 * Theo thiết kế promotion_outbox_pattern.md - Section 2: Kiến trúc Luồng.
 *
 * Phải được gọi từ @Transactional method để đảm bảo atomicity
 * giữa business data và outbox event.
 */
@Service
public class OutboxService {

    private final PromotionOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(PromotionOutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Lưu 1 outbox event row atomically.
     * Theo thiết kế promotion_outbox_pattern.md - Section 2:
     *   INSERT vào bảng promotion_outbox (status = PENDING) trong cùng Transaction.
     *
     * @param aggregateType VD: "PROMOTION", "MARKDOWN_RULE", "COUPON_USAGE"
     * @param aggregateId   ID entity liên quan
     * @param eventType     VD: "MARKDOWN_CREATED", "COUPON_REDEEMED"
     * @param payload       Nội dung Event
     */
    @Transactional
    public void publishEvent(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        PromotionOutbox outbox = new PromotionOutbox();
        outbox.setEventId(UUID.randomUUID());
        outbox.setAggregateType(aggregateType);
        outbox.setAggregateId(aggregateId);
        outbox.setEventType(eventType);
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setRetryCount(0);
        outbox.setMaxRetries(5);

        try {
            // Serialize payload thành JSON string để lưu vào JSONB column
            // Theo thiết kế promotion_outbox_pattern.md - Section 3: payload JSONB
            String payloadJson = objectMapper.writeValueAsString(payload);
            outbox.setPayload(payloadJson);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox payload", e);
        }

        outboxRepository.save(outbox);
    }
}
