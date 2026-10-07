package com.platform.paymentservice.application.service;

import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.RetentionPort;

import java.util.Objects;

public final class RetentionTrimmerService {
    private final RetentionPort retention;
    private final ClockPort clock;
    private final RetentionPort.Policy policy;

    public RetentionTrimmerService(RetentionPort retention, ClockPort clock,
                                   RetentionPort.Policy policy) {
        this.retention = Objects.requireNonNull(retention);
        this.clock = Objects.requireNonNull(clock);
        this.policy = Objects.requireNonNull(policy);
    }

    public RetentionPort.Result trim(int batchSize) {
        if (batchSize < 1 || batchSize > 5000)
            throw new IllegalArgumentException("retention batchSize must be 1..5000");
        return retention.trim(policy, clock.now(), batchSize);
    }
}
