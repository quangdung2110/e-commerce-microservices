package com.platform.productservice.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "category_attributes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryAttributeJpaEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "category_id", nullable = false)
    private String categoryId;

    @Column(name = "attribute_id", nullable = false)
    private String attributeId;

    @Column(name = "is_required", nullable = false)
    private Boolean isRequired = false;

    @Column(name = "is_variant", nullable = false)
    private Boolean isVariant = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", insertable = false, updatable = false)
    private CategoryJpaEntity category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attribute_id", insertable = false, updatable = false)
    private AttributeJpaEntity attribute;
}
