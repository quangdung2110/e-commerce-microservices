package com.ecommerce.checkout.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The system clock plus an adjustable offset. Redis TTLs still run in real time, so tests move this
 * clock forward to cross Checkout's own windows (quotation expiry, owner timeout, in-flight window,
 * retry window) without sleeping.
 */
public final class MutableClock extends Clock {

    private volatile long offsetMillis;

    public void advance(Duration amount) {
        offsetMillis += amount.toMillis();
    }

    public void reset() {
        offsetMillis = 0;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(System.currentTimeMillis() + offsetMillis);
    }
}
