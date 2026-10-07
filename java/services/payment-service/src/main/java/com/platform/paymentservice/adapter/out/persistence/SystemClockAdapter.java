package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.ClockPort;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public final class SystemClockAdapter implements ClockPort {
    @Override public Instant now() { return Instant.now(); }
}
