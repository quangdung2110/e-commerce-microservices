package com.ecommerce.pdp.resilience;

import java.time.Duration;

/**
 * Deadline PDP tính từ lúc BFF nhận request (ADR-004 §2). Dùng {@link System#nanoTime()}
 * nên không bị ảnh hưởng khi đồng hồ hệ thống nhảy.
 */
public final class Deadline {

    private final long expiresAtNanos;

    private Deadline(long expiresAtNanos) {
        this.expiresAtNanos = expiresAtNanos;
    }

    public static Deadline startingNow(Duration budget) {
        return new Deadline(System.nanoTime() + budget.toNanos());
    }

    /** Có thể âm khi đã quá hạn. */
    public Duration remaining() {
        return Duration.ofNanos(expiresAtNanos - System.nanoTime());
    }
}
