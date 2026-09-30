package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.domain.ProviderOperation;

public sealed interface ProviderOperationClaim {
    record Acquired(ProviderOperation operation, long leaseToken) implements ProviderOperationClaim {}
    record InProgress() implements ProviderOperationClaim {}
    record NotDue() implements ProviderOperationClaim {}
    record Conflict() implements ProviderOperationClaim {}
    record Terminal(ProviderOperation operation) implements ProviderOperationClaim {}
}
