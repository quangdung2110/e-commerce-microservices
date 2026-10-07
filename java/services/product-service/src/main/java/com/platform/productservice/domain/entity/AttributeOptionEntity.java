package com.platform.productservice.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Option của attribute {@code SELECT}. */
@Entity
@Table(name = "attribute_options")
@Getter
@Setter
@NoArgsConstructor
public class AttributeOptionEntity {

    @Id
    @Column(name = "option_id", nullable = false, updatable = false)
    private UUID optionId;

    @Column(name = "attribute_id", nullable = false)
    private UUID attributeId;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
