package com.platform.paymentservice.domain;

import java.util.Optional;

public record PaymentTransition(
        String transitionId,
        PaymentStatus from,
        PaymentStatus to,
        Optional<FinancialFact> fact) {
    public boolean financial() { return fact.isPresent(); }
}
