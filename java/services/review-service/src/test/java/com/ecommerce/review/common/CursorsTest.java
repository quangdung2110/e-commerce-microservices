package com.ecommerce.review.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Keyset cursors; Review never uses OFFSET (ADR-002). */
class CursorsTest {

    @Test
    void aCursorRoundTrips() {
        Instant createdAt = Instant.parse("2026-09-09T10:15:30.123456Z");
        UUID id = UUID.randomUUID();

        Cursors.Position decoded = Cursors.decode(Cursors.encode(createdAt, id));

        assertThat(decoded.timestamp()).isEqualTo(createdAt);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void anAbsentCursorMeansTheFirstPage() {
        assertThat(Cursors.decode(null)).isNull();
        assertThat(Cursors.decode("  ")).isNull();
    }

    @Test
    void aMalformedCursorIsARequestError() {
        assertThatThrownBy(() -> Cursors.decode("not-a-cursor"))
                .isInstanceOf(ReviewException.class)
                .satisfies(e -> assertThat(((ReviewException) e).code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    /** The encoding is opaque to clients; nothing readable leaks into it. */
    @Test
    void theCursorIsUrlSafeAndUnpadded() {
        String cursor = Cursors.encode(Instant.now(), UUID.randomUUID());

        assertThat(cursor).doesNotContain("=", "+", "/");
        assertThat(cursor.length()).isLessThanOrEqualTo(512);
    }
}
