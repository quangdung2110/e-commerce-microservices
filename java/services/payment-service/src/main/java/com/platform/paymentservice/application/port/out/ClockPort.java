package com.platform.paymentservice.application.port.out;

import java.time.Instant;

public interface ClockPort {
    Instant now();
}
