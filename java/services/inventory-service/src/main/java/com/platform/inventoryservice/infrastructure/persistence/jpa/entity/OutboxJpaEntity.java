package com.platform.inventoryservice.infrastructure.persistence.jpa.entity;

import com.platform.inventoryservice.domain.enums.OutboxEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class OutboxJpaEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 100)
    private OutboxEventType eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 255)
    private String aggregateId;

    @Column(name = "partition_key", nullable = false, length = 255)
    private String partitionKey;

    @Column(name = "refund_request_id", length = 64)
    private String refundRequestId;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
