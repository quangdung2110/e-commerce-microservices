package com.ecommerce.checkout.crypto;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Cryptographically random identifiers for tokens and attempts (CHK-INV-01: a checkout token is a UUIDv4
 * from a CSPRNG). {@link UUID#randomUUID()} and a default {@link SecureRandom} read {@code /dev/urandom} on
 * every call, which is a blocking file read on the event loop; a DRBG seeded once at startup generates
 * the same quality of randomness without touching a file per request.
 */
public final class SecureIds {

    private static final SecureRandom RANDOM = create();

    private SecureIds() {
    }

    private static SecureRandom create() {
        try {
            SecureRandom random = SecureRandom.getInstance("DRBG");
            random.nextBytes(new byte[1]); // seed now, at class loading, never on a request
            return random;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("the DRBG SecureRandom is unavailable", e);
        }
    }

    /** The shared generator, for IVs and data keys. */
    public static SecureRandom random() {
        return RANDOM;
    }

    /** A random (version 4) UUID in lowercase canonical form. */
    public static String uuid() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        long most = 0;
        long least = 0;
        for (int i = 0; i < 8; i++) {
            most = (most << 8) | (bytes[i] & 0xff);
            least = (least << 8) | (bytes[8 + i] & 0xff);
        }
        return new UUID(most, least).toString();
    }
}
