package com.ecommerce.review.storage;

import com.ecommerce.review.domain.MediaType;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Generates the opaque storage key of a media asset.
 *
 * <p>The key contains no user identifier, no review identifier and no asset identifier:
 * it is unguessable random material under a date prefix, and it is never returned by any
 * API ({@code docs/review/data.md 4}). Callers exchange {@code asset_id} and signed URLs
 * instead, so the key can never be used to address an object directly.
 */
@Component
public class ObjectKeys {

    private static final DateTimeFormatter DATE_PREFIX =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int RANDOM_BYTES = 24;

    private final SecureRandom random = new SecureRandom();

    public String generate(MediaType mediaType, Instant now) {
        byte[] material = new byte[RANDOM_BYTES];
        random.nextBytes(material);
        return "review-media/" + mediaType.name().toLowerCase(java.util.Locale.ROOT) + '/'
                + DATE_PREFIX.format(now) + '/' + ENCODER.encodeToString(material);
    }
}
