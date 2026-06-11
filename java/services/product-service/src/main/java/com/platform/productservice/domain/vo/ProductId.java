package com.platform.productservice.domain.vo;

import java.io.Serializable;
import java.util.Objects;

/**
 * Product ID value object.
 * Uses ULID for globally unique, time-sortable identifiers.
 */
public final class ProductId implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String value;

    private ProductId(String value) {
        this.value = Objects.requireNonNull(value, "ProductId value must not be null");
    }

    public static ProductId generate() {
        return new ProductId(ulid());
    }

    public static ProductId of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ProductId value must not be blank");
        }
        return new ProductId(value.trim());
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProductId productId = (ProductId) o;
        return Objects.equals(value, productId.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }

    private static String ulid() {
        return com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();
    }
}
