package com.ecommerce.review.repository;

/**
 * Bind placeholders for a bounded {@code IN} list.
 *
 * <p>Batch loads use an explicit placeholder list rather than a driver array because
 * the identifiers are always a page of rows Review just read, and every count is bounded
 * by a page limit ({@code docs/review/data.md 10}).
 */
final class Placeholders {

    private Placeholders() {
    }

    static String of(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("an IN list needs at least one value");
        }
        return "?" + ", ?".repeat(count - 1);
    }
}
