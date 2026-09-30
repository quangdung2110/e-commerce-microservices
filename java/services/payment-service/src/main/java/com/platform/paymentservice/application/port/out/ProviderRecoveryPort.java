package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.domain.ProviderOperation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface ProviderRecoveryPort {
    List<Claimed> claimDue(String owner, Instant now, Duration leaseTtl, int batchSize);

    sealed interface Claimed permits Create, Cancel, Refund {
        ProviderOperation operation();
        long commandLeaseToken();
        long operationLeaseToken();
    }

    record Create(ProviderOperation operation, long commandLeaseToken,
                  long operationLeaseToken, long orderId, int paymentAttemptNumber,
                  long amountMinor, Instant paymentExpiresAt) implements Claimed {}

    record Cancel(ProviderOperation operation, long commandLeaseToken,
                  long operationLeaseToken, long orderId, int paymentAttemptNumber,
                  long amountMinor, String currency, String paymentStatus,
                  int paymentVersion, long financialStateVersion,
                  String providerTransactionId, String operationReason,
                  boolean commandRequest, String terminalErrorCode) implements Claimed {}

    record Refund(ProviderOperation operation, long commandLeaseToken,
                  long operationLeaseToken, long orderId, int paymentAttemptNumber,
                  long amountMinor, String currency, int paymentVersion,
                  long financialStateVersion) implements Claimed {}
}
