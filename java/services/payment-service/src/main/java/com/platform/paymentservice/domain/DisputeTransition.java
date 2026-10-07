package com.platform.paymentservice.domain;

import java.util.Optional;

public record DisputeTransition(
        String transitionId, DisputeState from, DisputeState to, Optional<FinancialFact> fact) {}
