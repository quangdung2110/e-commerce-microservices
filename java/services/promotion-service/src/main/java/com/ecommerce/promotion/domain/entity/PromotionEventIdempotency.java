package com.ecommerce.promotion.domain.entity;

import com.ecommerce.promotion.domain.enums.IdempotencyActionType;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Chống lặp Message Kafka (Vòng 2 - Database Level).
 * Theo thiết kế promotion_db_design.md - Section 1.7
 *
 * Kết hợp cùng Redis SETNX (Vòng 1) tạo thành Distributed Saga Guard.
 * Theo thiết kế redis_caching_design.md - Idempotency Kép.
 */
@Entity
@Table(name = "promotion_event_idempotency")
public class PromotionEventIdempotency {

    @Id
    @Column(name = "event_id", length = 255)
    private String eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 50)
    private IdempotencyActionType actionType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @PrePersist
    protected void onCreate() {
        if (this.processedAt == null) {
            this.processedAt = Instant.now();
        }
    }

    // ---- Constructors ----

    public PromotionEventIdempotency() {}

    public PromotionEventIdempotency(String eventId, Long orderId, IdempotencyActionType actionType) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.actionType = actionType;
        this.processedAt = Instant.now();
    }

    // ---- Getters / Setters ----

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public IdempotencyActionType getActionType() { return actionType; }
    public void setActionType(IdempotencyActionType actionType) { this.actionType = actionType; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
