package com.platform.paymentservice.domain;

import java.util.Objects;

public record DisputeState(DisputeStatus status, DisputeFundsState fundsState) {
    public DisputeState { Objects.requireNonNull(status); Objects.requireNonNull(fundsState); }
}
