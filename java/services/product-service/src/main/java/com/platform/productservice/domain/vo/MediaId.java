package com.platform.productservice.domain.vo;

import java.io.Serializable;
import java.util.Objects;

/**
 * Media ID value object.
 */
public final class MediaId implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String value;

    private MediaId(String value) {
        this.value = Objects.requireNonNull(value, "MediaId value must not be null");
    }

    public static MediaId generate() {
        return new MediaId(com.github.f4b6a3.ulid.UlidCreator.getUlid().create().toString());
    }

    public static MediaId of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MediaId value must not be blank");
        }
        return new MediaId(value.trim());
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MediaId mediaId = (MediaId) o;
        return Objects.equals(value, mediaId.value);
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
