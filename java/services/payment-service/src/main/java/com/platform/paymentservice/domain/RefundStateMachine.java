package com.platform.paymentservice.domain;

import java.util.Map;

public final class RefundStateMachine {
    private record Key(RefundStatus status, RefundTrigger trigger) {}
    private record Definition(String id, RefundStatus target, FinancialFact fact) {}
    private static final Map<Key, Definition> TRANSITIONS = Map.of(
        new Key(RefundStatus.PENDING, RefundTrigger.REFUND_REQUEST_SENT),
            new Definition("R1", RefundStatus.PROCESSING, FinancialFact.REFUND_PROCESSING),
        new Key(RefundStatus.PROCESSING, RefundTrigger.REFUND_SUCCESS_CONFIRMED),
            new Definition("R2", RefundStatus.SUCCESS, FinancialFact.REFUND_SUCCESS),
        new Key(RefundStatus.PROCESSING, RefundTrigger.REFUND_FAILED_CONFIRMED),
            new Definition("R3", RefundStatus.FAILED, FinancialFact.REFUND_FAILED),
        new Key(RefundStatus.PENDING, RefundTrigger.REFUND_REQUEST_REJECTED),
            new Definition("R4", RefundStatus.FAILED, FinancialFact.REFUND_FAILED),
        new Key(RefundStatus.SUCCESS, RefundTrigger.REFUND_REVERSED_CONFIRMED),
            new Definition("R5", RefundStatus.REVERSED, FinancialFact.REFUND_REVERSED)
    );

    public RefundTransition transition(RefundStatus current, RefundTrigger trigger) {
        Definition definition = TRANSITIONS.get(new Key(current, trigger));
        if (definition == null) throw new InvalidTransitionException(current, trigger);
        return new RefundTransition(definition.id(), current, definition.target(), definition.fact());
    }
}
