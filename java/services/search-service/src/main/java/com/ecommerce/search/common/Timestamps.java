package com.ecommerce.search.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Every timestamp written to Elasticsearch is ISO-8601 UTC with millisecond precision, as
 * {@code docs/search/schema/scripts/README.md} requires. Fixing the precision keeps string
 * comparison and time comparison in agreement.
 */
public final class Timestamps {

    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private Timestamps() {
    }

    public static String iso(Instant instant) {
        return ISO_MILLIS.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    public static String nowIso() {
        return iso(Instant.now());
    }
}
