package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.domain.ProviderOperation;

import java.time.Duration;
import java.time.Instant;

public interface ProviderOperationPort {
    ProviderOperationClaim createOrClaim(ProviderOperation operation, String owner,
                                         Instant now, Duration leaseTtl);

    boolean markDispatched(String operationId, String owner, long leaseToken,
                           Instant dispatchedAt, Duration safeReplayHorizon);

    boolean finish(String operationId, String owner, long leaseToken,
                   ProviderOperationResult result, Instant finishedAt, Instant nextRetryAt);

    boolean heartbeat(String operationId, String owner, long leaseToken,
                      Instant now, Duration leaseTtl);
}
