package com.platform.paymentservice.domain;

import java.util.Map;
import java.util.Optional;

public final class PaymentStateMachine {
    private record Key(PaymentStatus status, PaymentTrigger trigger) {}
    private record Definition(String id, PaymentStatus target, FinancialFact fact) {}

    private static final Map<Key, Definition> TRANSITIONS = Map.ofEntries(
        entry(PaymentStatus.INITIATING, PaymentTrigger.PROVIDER_INTENT_IDENTIFIED, "T0.1", PaymentStatus.PENDING, FinancialFact.PAYMENT_INITIATED),
        entry(PaymentStatus.PENDING, PaymentTrigger.PROVIDER_PROCESSING, "T1", PaymentStatus.PROCESSING, null),
        entry(PaymentStatus.PENDING, PaymentTrigger.PAYMENT_SUCCESS_CONFIRMED, "T2", PaymentStatus.SUCCESS, FinancialFact.PAYMENT_SUCCESS),
        entry(PaymentStatus.PROCESSING, PaymentTrigger.PAYMENT_SUCCESS_CONFIRMED, "T2", PaymentStatus.SUCCESS, FinancialFact.PAYMENT_SUCCESS),
        entry(PaymentStatus.PENDING, PaymentTrigger.PAYMENT_DECLINED, "T3a", PaymentStatus.PENDING, null),
        entry(PaymentStatus.PROCESSING, PaymentTrigger.PAYMENT_DECLINED, "T3a", PaymentStatus.PENDING, null),
        entry(PaymentStatus.PENDING, PaymentTrigger.PAYMENT_FAILED_CONFIRMED, "T3", PaymentStatus.FAILED, FinancialFact.PAYMENT_FAILED),
        entry(PaymentStatus.PENDING, PaymentTrigger.PAYMENT_SESSION_EXPIRED, "T4", PaymentStatus.EXPIRED, FinancialFact.PAYMENT_EXPIRED),
        entry(PaymentStatus.PROCESSING, PaymentTrigger.PAYMENT_SESSION_EXPIRED, "T4", PaymentStatus.EXPIRED, FinancialFact.PAYMENT_EXPIRED),
        entry(PaymentStatus.PENDING, PaymentTrigger.PAYMENT_CANCELED, "T5", PaymentStatus.CANCELED, FinancialFact.PAYMENT_CANCELED),
        entry(PaymentStatus.PROCESSING, PaymentTrigger.PAYMENT_CANCELED, "T5", PaymentStatus.CANCELED, FinancialFact.PAYMENT_CANCELED),
        entry(PaymentStatus.SUCCESS, PaymentTrigger.REFUND_REQUEST_ACCEPTED, "T6", PaymentStatus.REFUNDING, FinancialFact.REFUND_INITIATED),
        entry(PaymentStatus.REFUNDING, PaymentTrigger.REFUND_SUCCESS_CONFIRMED, "T7", PaymentStatus.REFUNDED, FinancialFact.REFUND_SUCCESS),
        entry(PaymentStatus.REFUNDING, PaymentTrigger.REFUND_FAILED_CONFIRMED, "T8", PaymentStatus.SUCCESS, FinancialFact.REFUND_FAILED),
        entry(PaymentStatus.REFUNDED, PaymentTrigger.REFUND_REVERSED_CONFIRMED, "T9", PaymentStatus.SUCCESS, FinancialFact.REFUND_REVERSED)
    );

    private static Map.Entry<Key, Definition> entry(
            PaymentStatus from, PaymentTrigger trigger, String id,
            PaymentStatus to, FinancialFact fact) {
        return Map.entry(new Key(from, trigger), new Definition(id, to, fact));
    }

    public PaymentTransition transition(PaymentStatus current, PaymentTrigger trigger) {
        Definition definition = TRANSITIONS.get(new Key(current, trigger));
        if (definition == null) throw new InvalidTransitionException(current, trigger);
        return new PaymentTransition(definition.id(), current, definition.target(),
                Optional.ofNullable(definition.fact()));
    }
}
