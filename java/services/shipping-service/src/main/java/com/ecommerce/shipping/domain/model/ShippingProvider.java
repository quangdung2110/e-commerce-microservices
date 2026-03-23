package com.ecommerce.shipping.domain.model;

// Theo thiết kế shipping_db_design.md mục 1.1: shipping providers catalog.
public record ShippingProvider(
        Integer id,
        String code,
        String name,
        boolean active,
        Integer priority
) {
}
