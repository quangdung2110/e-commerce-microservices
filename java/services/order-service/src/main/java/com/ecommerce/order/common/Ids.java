package com.ecommerce.order.common;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;
import java.util.regex.Pattern;

/** Identifier helpers. Order-owned BIGINT IDs cross JSON as canonical unpadded strings. */
public final class Ids {

    private static final Pattern CANONICAL_ID = Pattern.compile("^[1-9][0-9]{0,18}$");

    private Ids() {
    }

    public static UUID newUuid() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    /** Parses an Order-owned positive BIGINT wire ID; returns null when not canonical. */
    public static Long parseCanonical(String value) {
        if (value == null || !CANONICAL_ID.matcher(value).matches()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String wire(long id) {
        return Long.toString(id);
    }

    public static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equalsIgnoreCase(value) ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
