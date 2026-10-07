package com.ecommerce.checkout.resilience;

import java.time.Duration;

/**
 * Deadline of a Checkout route, counted from the moment the request is received (ADR-014).
 * Uses {@link System#nanoTime()} so a system clock jump cannot move it.
 */
public final class Deadline {

    private final long expiresAtNanos;

    private Deadline(long expiresAtNanos) {
        this.expiresAtNanos = expiresAtNanos;
    }

    public static Deadline startingNow(Duration budget) {
        return new Deadline(System.nanoTime() + budget.toNanos());
    }

    /** May be negative once the deadline has passed. */
    public Duration remaining() {
        return Duration.ofNanos(expiresAtNanos - System.nanoTime());
    }
}
