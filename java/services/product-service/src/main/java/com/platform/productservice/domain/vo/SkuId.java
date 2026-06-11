package com.platform.productservice.domain.vo;

import java.io.Serializable;
import java.util.Objects;

/**
 * SKU ID value object.
 * Uses ULID for globally unique, time-sortable identifiers.
 */
public final class SkuId implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String value;

    private SkuId(String value) {
        this.value = Objects.requireNonNull(value, "SkuId value must not be null");
    }

    public static SkuId generate() {
        return new SkuId(ulid());
    }

    public static SkuId of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SkuId value must not be blank");
        }
        return new SkuId(value.trim());
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SkuId skuId = (SkuId) o;
        return Objects.equals(value, skuId.value);
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
