package com.ecommerce.review.common;

import java.util.UUID;

/** UUID helpers shared by the HMAC derivations and the CloudEvents envelope. */
public final class Uuids {

    private Uuids() {
    }

    /**
     * The canonical lowercase form both HMAC inputs are defined over (ADR-007). Java's
     * {@link UUID#toString()} is already lowercase, but the derivations are specified
     * against a canonical form, so the intent is stated rather than inherited.
     */
    public static String canonical(UUID id) {
        return id.toString().toLowerCase(java.util.Locale.ROOT);
    }

    /** Parses a caller-supplied identifier, or returns null when it is not a UUID. */
    public static UUID parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
