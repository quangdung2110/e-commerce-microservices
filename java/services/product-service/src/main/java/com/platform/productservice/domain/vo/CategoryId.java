package com.platform.productservice.domain.vo;

import java.io.Serializable;
import java.util.Objects;

/**
 * Category ID value object.
 * Uses ULID for globally unique, time-sortable identifiers.
 */
public final class CategoryId implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String value;

    private CategoryId(String value) {
        this.value = Objects.requireNonNull(value, "CategoryId value must not be null");
    }

    public static CategoryId generate() {
        return new CategoryId(ulid());
    }

    public static CategoryId of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CategoryId value must not be blank");
        }
        return new CategoryId(value.trim());
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CategoryId categoryId = (CategoryId) o;
        return Objects.equals(value, categoryId.value);
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
        return com.github.f4b6a3.ulid.UlidCreator.getUlid().create().toString();
    }
}
