package com.platform.paymentservice.application.service;

import com.platform.paymentservice.application.CanonicalCommand;
import com.platform.paymentservice.application.CanonicalFingerprint;
import com.platform.paymentservice.application.IdempotencyAction;
import com.platform.paymentservice.application.port.out.IdempotencyClaim;
import com.platform.paymentservice.application.port.out.IdempotencyPort;
import com.platform.paymentservice.application.port.out.StoredHttpResponse;
import com.platform.paymentservice.application.port.out.ClockPort;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

public final class IdempotencyExecutor {
    public enum Outcome { EXECUTED, REPLAYED, IN_PROGRESS, KEY_REUSED, FENCED }
    public record Result(Outcome outcome, StoredHttpResponse response) {}

    private final IdempotencyPort port;
    private final ClockPort clock;
    private final CanonicalFingerprint fingerprints;

    public IdempotencyExecutor(IdempotencyPort port, ClockPort clock) {
        this.port = port;
        this.clock = clock;
        this.fingerprints = new CanonicalFingerprint();
    }

    public Result execute(String key, IdempotencyAction action, CanonicalCommand command,
                          String owner, Instant now, Duration leaseTtl, Instant expiresAt,
                          Supplier<StoredHttpResponse> operation) {
        String fingerprint = fingerprints.sha256(command);
        IdempotencyClaim claim = port.claim(key, action, fingerprint, owner, now, leaseTtl, expiresAt);
        if (claim instanceof IdempotencyClaim.Completed completed)
            return new Result(Outcome.REPLAYED, completed.response());
        if (claim instanceof IdempotencyClaim.InProgress)
            return new Result(Outcome.IN_PROGRESS, null);
        if (claim instanceof IdempotencyClaim.KeyReused)
            return new Result(Outcome.KEY_REUSED, null);
        long token = ((IdempotencyClaim.Acquired) claim).leaseToken();
        StoredHttpResponse response = operation.get();
        boolean completed = port.complete(key, action, owner, token, response, clock.now());
        return completed ? new Result(Outcome.EXECUTED, response) : new Result(Outcome.FENCED, null);
    }
}
