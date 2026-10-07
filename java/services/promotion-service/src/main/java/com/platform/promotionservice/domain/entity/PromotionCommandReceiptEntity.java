package com.platform.promotionservice.domain.entity;

import com.platform.promotionservice.domain.enums.CommandOperation;
import com.platform.promotionservice.domain.enums.WalletStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable receipt cho final command CONFIRM / CANCEL_CONFIRMED (ADR-013).
 * {@code redis_finalized_at} là durable trigger cho Redis finalize/repair
 * (ADR-016); luôn NULL khi transaction commit.
 */
@Entity
@Table(name = "promotion_command_receipts")
@Getter
@Setter
public class PromotionCommandReceiptEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 24)
    private CommandOperation operationType;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "promotion_id", nullable = false)
    private Long promotionId;

    @Column(name = "order_id", nullable = false)
    private long orderId;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    /** SHA-256 hex của canonical request; CHAR(64) theo data.md §3.8. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "amount_vnd", nullable = false)
    private long amountVnd;

    @Column(name = "result_code", nullable = false, length = 64)
    private String resultCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "resulting_wallet_status", nullable = false, length = 16)
    private WalletStatus resultingWalletStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", nullable = false, columnDefinition = "jsonb")
    private String responsePayload;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(name = "redis_finalized_at")
    private Instant redisFinalizedAt;

    @Column(name = "retain_until", nullable = false)
    private Instant retainUntil;
}
