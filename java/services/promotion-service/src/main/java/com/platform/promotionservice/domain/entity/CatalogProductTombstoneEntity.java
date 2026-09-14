package com.platform.promotionservice.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Terminal watermark của Product đã hard delete. Chặn markdown rule xuất hiện
 * sau khi delete đã được quan sát (PROMO-INV-018).
 */
@Entity
@Table(name = "catalog_product_tombstones")
@Getter
@Setter
public class CatalogProductTombstoneEntity {

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "source_product_revision", nullable = false)
    private long sourceProductRevision;

    @Column(name = "product_deleted_at", nullable = false)
    private Instant productDeletedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
