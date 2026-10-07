package com.platform.productservice.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mapping category–attribute. {@code isVariant=true} nghĩa là attribute thuộc
 * {@code variant_attributes} của SKU; ngược lại thuộc {@code attributes} của Product.
 */
@Entity
@Table(name = "category_attributes")
@Getter
@Setter
@NoArgsConstructor
public class CategoryAttributeEntity {

    @EmbeddedId
    private CategoryAttributeId id;

    @Column(name = "is_required", nullable = false)
    private boolean required;

    @Column(name = "is_variant", nullable = false)
    private boolean variant;
}
