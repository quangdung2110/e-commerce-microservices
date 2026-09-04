package com.platform.productservice.domain.entity;

import com.platform.productservice.domain.enums.MediaType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Media cấp Product ({@code skuId == null}) hoặc gắn với một SKU của chính
 * Product đó; composite FK trong schema bảo đảm ràng buộc này.
 */
@Entity
@Table(name = "product_media")
@Getter
@Setter
@NoArgsConstructor
public class ProductMediaEntity {

    @Id
    @Column(name = "media_id", nullable = false, updatable = false)
    private UUID mediaId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "sku_id")
    private UUID skuId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 16)
    private MediaType mediaType;

    @Column(name = "storage_key", nullable = false, length = 1024)
    private String storageKey;

    @Column(name = "alt_text", length = 255)
    private String altText;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
