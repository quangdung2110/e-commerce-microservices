package com.platform.productservice.domain.entity;

import com.platform.productservice.domain.enums.AttributeValueType;
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

/** Attribute cấu hình dùng chung cho mọi category. Không có event riêng ở v1. */
@Entity
@Table(name = "attributes")
@Getter
@Setter
@NoArgsConstructor
public class AttributeEntity {

    @Id
    @Column(name = "attribute_id", nullable = false, updatable = false)
    private UUID attributeId;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 16)
    private AttributeValueType valueType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
