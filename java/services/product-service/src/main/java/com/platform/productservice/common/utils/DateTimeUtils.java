package com.platform.productservice.common.utils;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class DateTimeUtils {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private DateTimeUtils() {}

    public static long toEpochMillis(Instant instant) {
        if (instant == null) return 0L;
        return instant.toEpochMilli();
    }

    public static Instant fromEpochMillis(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }

    public static String toIsoString(Instant instant) {
        if (instant == null) return null;
        return ISO_FORMATTER.format(instant);
    }

    public static Instant parseIsoString(String isoString) {
        if (isoString == null || isoString.isBlank()) return null;
        try {
            return Instant.parse(isoString);
        } catch (DateTimeParseException e) {
            throw new RuntimeException("Failed to parse ISO timestamp: " + isoString, e);
        }
    }

    public static boolean isExpired(Timestamp expiresAt, Instant now) {
        if (expiresAt == null) return false;
        return expiresAt.toInstant().isBefore(now);
    }

    public static boolean isExpired(Instant expiresAt, Instant now) {
        if (expiresAt == null) return false;
        return expiresAt.isBefore(now);
    }
}
