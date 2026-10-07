package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.application.IdempotencyAction;

import java.time.Duration;
import java.time.Instant;

public interface IdempotencyPort {
    IdempotencyClaim claim(String key, IdempotencyAction action, String fingerprint,
                           String owner, Instant now, Duration leaseTtl, Instant expiresAt);

    boolean complete(String key, IdempotencyAction action, String owner, long leaseToken,
                     StoredHttpResponse response, Instant completedAt);

    boolean heartbeat(String key, IdempotencyAction action, String owner, long leaseToken,
                      Instant now, Duration leaseTtl);
}
