package com.platform.productservice.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CategoryAttributeId implements Serializable {

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "attribute_id", nullable = false)
    private UUID attributeId;
}
