package com.platform.promotionservice.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only transactional outbox; ở v1 chỉ chứa markdown snapshot. {@code payload}
 * là CloudEvents structured JSON hoàn chỉnh; connector chỉ chọn topic/key/header.
 */
@Entity
@Table(name = "promotion_outbox_events")
@Getter
@Setter
public class PromotionOutboxEventEntity {

    public static final String AGGREGATE_PRODUCT = "Product";

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 32)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "partition_key", nullable = false, length = 64)
    private String partitionKey;

    @Column(name = "source_revision")
    private Long sourceRevision;

    @Column(name = "dedup_key", nullable = false)
    private String dedupKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
