package com.platform.paymentservice.domain;

public record RefundTransition(
        String transitionId, RefundStatus from, RefundStatus to, FinancialFact fact) {}
