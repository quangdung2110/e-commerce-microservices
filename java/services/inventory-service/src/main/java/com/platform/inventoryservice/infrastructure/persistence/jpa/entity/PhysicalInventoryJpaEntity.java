package com.platform.inventoryservice.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

@Entity
@Table(name = "physical_inventory")
@Getter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class PhysicalInventoryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sku_id", nullable = false, length = 64)
    private String skuId;

    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "actual_quantity", nullable = false)
    private int actualQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    /** GENERATED ALWAYS AS STORED — KHÔNG insert/update từ app. */
    @Generated(event = EventType.INSERT)
    @Column(name = "available_quantity", insertable = false, updatable = false)
    private int availableQuantity;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PreUpdate
    @PrePersist
    void touch() {
        this.updatedAt = Instant.now();
    }
}
