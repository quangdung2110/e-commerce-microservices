package com.platform.inventoryservice.infrastructure.persistence.jpa.entity;

import com.platform.inventoryservice.domain.enums.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class InventoryReservationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "sku_id", nullable = false, length = 64)
    private String skuId;

    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "route_plan_id", nullable = false)
    private UUID routePlanId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "refund_expected", nullable = false)
    private boolean refundExpected;

    @Column(name = "refund_request_id", length = 64)
    private String refundRequestId;

    @Column(name = "refund_returned_at")
    private Instant refundReturnedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
