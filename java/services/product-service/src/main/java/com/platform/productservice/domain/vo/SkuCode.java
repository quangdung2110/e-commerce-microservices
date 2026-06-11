package com.platform.productservice.domain.vo;

import java.io.Serializable;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SKU Code value object.
 * Alphanumeric with dashes, max 255 characters, must be unique.
 */
public final class SkuCode implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final int MAX_LENGTH = 255;
    private static final Pattern VALID_PATTERN = Pattern.compile("^[A-Za-z0-9\\-]+$");

    private final String value;

    private SkuCode(String value) {
        this.value = value;
    }

    public static SkuCode of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SkuCode must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("SkuCode must not exceed " + MAX_LENGTH + " characters");
        }
        if (!VALID_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "SkuCode must contain only alphanumeric characters and dashes");
        }
        return new SkuCode(trimmed);
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SkuCode skuCode = (SkuCode) o;
        return Objects.equals(value, skuCode.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
