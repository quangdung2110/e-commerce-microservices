package com.platform.productservice.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "attribute_values")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttributeValueJpaEntity {

    @Id
    @Column(name = "value_id")
    private String valueId;

    @Column(name = "attribute_id", nullable = false)
    private String attributeId;

    @Column(name = "value", nullable = false)
    private String value;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attribute_id", insertable = false, updatable = false)
    private AttributeJpaEntity attribute;
}
