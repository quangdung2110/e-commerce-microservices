package com.ecommerce.review.dto;

/**
 * A JSON field that may be absent, present with a value, or present and null.
 *
 * <p>{@link java.util.Optional} cannot express the third case, and that is exactly the
 * case the PATCH contract turns on: an absent {@code content} is preserved while
 * {@code content: null} clears the text. Collapsing the two would erase a review body on
 * every rating-only edit.
 */
public record Patch<T>(boolean present, T value) {

    private static final Patch<?> ABSENT = new Patch<>(false, null);

    @SuppressWarnings("unchecked")
    public static <T> Patch<T> absent() {
        return (Patch<T>) ABSENT;
    }

    public static <T> Patch<T> of(T value) {
        return new Patch<>(true, value);
    }

    /** The value when the field was present, otherwise the current value. */
    public T orElse(T current) {
        return present ? value : current;
    }
}
