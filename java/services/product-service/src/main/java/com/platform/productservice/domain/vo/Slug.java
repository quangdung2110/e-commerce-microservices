package com.platform.productservice.domain.vo;

import java.io.Serializable;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Slug value object for URL-friendly identifiers.
 * Normalized to lowercase, alphanumeric with hyphens only.
 */
public final class Slug implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final int MAX_LENGTH = 255;
    private static final Pattern VALID_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private final String value;

    private Slug(String value) {
        this.value = value;
    }

    public static Slug of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Slug must not be blank");
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Slug must not exceed " + MAX_LENGTH + " characters");
        }
        if (!VALID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Slug must contain only lowercase alphanumeric characters and hyphens, " +
                    "with no leading/trailing hyphens or consecutive hyphens");
        }
        return new Slug(normalized);
    }

    public String getValue() {
        return value;
    }

    public String toUrl() {
        return "/category/" + value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Slug slug = (Slug) o;
        return Objects.equals(value, slug.value);
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
