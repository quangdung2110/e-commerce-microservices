package com.ecommerce.search.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.format.DateTimeParseException;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TimeUtils {

    /**
     * Parse an ISO 8601 string to Instant.
     *
     * @param iso8601 the ISO 8601 string (e.g. "2026-02-28T03:51:00Z")
     * @return the parsed Instant, or null if parsing fails
     */
    public static Instant toInstant(String iso8601) {
        if (iso8601 == null || iso8601.isBlank()) return null;
        try {
            return Instant.parse(iso8601);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Convert an Instant to epoch milliseconds.
     */
    public static long toEpochMillis(Instant instant) {
        return instant.toEpochMilli();
    }

    /**
     * Create an Instant from epoch milliseconds.
     */
    public static Instant fromEpochMillis(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }

    /**
     * Format an Instant as an ISO 8601 string.
     */
    public static String toIsoString(Instant instant) {
        if (instant == null) return null;
        return instant.toString();
    }
}
