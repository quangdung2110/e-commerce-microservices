package com.platform.productservice.domain.enums;

/**
 * Giá trị hợp lệ của {@code product_outbox_events.aggregate_type}.
 * Event Product/SKU đều dùng {@link #Product} với aggregate_id = product_id
 * (ADR-012).
 */
public enum AggregateType {
    Product,
    Category
}
