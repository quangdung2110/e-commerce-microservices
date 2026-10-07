package com.ecommerce.review.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset cursors.
 *
 * <p>Review never uses {@code OFFSET} (ADR-002), so every page carries the last row's
 * sort key. The encoding is base64url of the raw key: these values are a timestamp and
 * a Review-owned UUID that the very same page already returned, so there is nothing to
 * hide and nothing that a forged cursor could reach beyond a differently-ordered page
 * of the same public data. Every read that accepts a cursor still applies its own
 * ownership and {@code status} predicate.
 */
public final class Cursors {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** A keyset position: an instant plus the row id that breaks its ties. */
    public record Position(Instant timestamp, UUID id) {
    }

    private Cursors() {
    }

    public static String encode(Instant timestamp, UUID id) {
        return ENCODER.encodeToString((timestamp.toString() + '|' + id).getBytes(StandardCharsets.UTF_8));
    }

    public static Position decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            if (separator < 0) {
                throw new IllegalArgumentException("missing separator");
            }
            return new Position(Instant.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw ReviewException.invalid("cursor", "cursor is not a cursor this endpoint issued");
        }
    }
}
