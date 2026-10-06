package com.ecommerce.review.privacy;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The 32-byte HMAC deletion-guard key of one privacy subject.
 *
 * <p>It is matched for equality and nothing else. It must never be logged, exported,
 * used as a metric label or used for authorization (ADR-007).
 *
 * @param bytes the raw {@code privacy_erasure_requests.subject_match_key}
 * @param version {@code subject_key_version}; a rotation needs both versions to coexist
 *                until every guard under the old key is gone
 */
public record SubjectKey(byte[] bytes, short version) {

    public SubjectKey {
        if (bytes == null || bytes.length != 32) {
            throw new IllegalArgumentException("a subject match key is exactly 32 bytes");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * The advisory-lock identifier of this subject.
     *
     * <p>Every path that can write a direct customer identifier takes
     * {@code pg_advisory_xact_lock} on this value before it checks the guard, which is
     * what serialises the Order consumer, the customer write commands and the
     * notification branch of an admin reply or takedown against the privacy consumer
     * (REVIEW-INV-012). Taking the first eight bytes of an HMAC keeps the mapping
     * deterministic and uniform; a collision would only make two unrelated subjects
     * share a lock briefly, which costs a little contention and no correctness.
     */
    public long lockKey() {
        return ByteBuffer.wrap(bytes, 0, Long.BYTES).getLong();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SubjectKey key
                && version == key.version
                && Arrays.equals(bytes, key.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(bytes) + version;
    }

    /** Deliberately opaque so a stray log line cannot leak the guard. */
    @Override
    public String toString() {
        return "SubjectKey[v" + version + ", redacted]";
    }
}
