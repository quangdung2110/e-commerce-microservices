package com.platform.productservice.domain;

import com.platform.productservice.domain.enums.AttributeValueType;

/**
 * Descriptor của một attribute được map vào category, đi kèm Category snapshot và
 * Category export để Search dựng facet ổn định (ADR-019).
 *
 * <p>{@code label} là {@code attributes.name} do admin đặt; {@code type} là
 * {@code attributes.value_type}. Không có thông tin per-category (required, variant):
 * label và type thuộc về attribute nên một {@code code} có cùng descriptor ở mọi
 * category mapping nó.
 */
public record FacetDefinition(String code, String label, AttributeValueType type) {
}
