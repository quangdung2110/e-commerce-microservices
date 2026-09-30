package com.platform.paymentservice.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStateMachineTest {
    private record Valid(PaymentStatus from, PaymentTrigger trigger, String id,
                         PaymentStatus to, FinancialFact fact) {}

    private static final Set<Valid> VALID = Set.of(
        new Valid(PaymentStatus.INITIATING, PaymentTrigger.PROVIDER_INTENT_IDENTIFIED, "T0.1", PaymentStatus.PENDING, FinancialFact.PAYMENT_INITIATED),
        new Valid(PaymentStatus.PENDING, PaymentTrigger.PROVIDER_PROCESSING, "T1", PaymentStatus.PROCESSING, null),
        new Valid(PaymentStatus.PENDING, PaymentTrigger.PAYMENT_SUCCESS_CONFIRMED, "T2", PaymentStatus.SUCCESS, FinancialFact.PAYMENT_SUCCESS),
        new Valid(PaymentStatus.PROCESSING, PaymentTrigger.PAYMENT_SUCCESS_CONFIRMED, "T2", PaymentStatus.SUCCESS, FinancialFact.PAYMENT_SUCCESS),
        new Valid(PaymentStatus.PENDING, PaymentTrigger.PAYMENT_DECLINED, "T3a", PaymentStatus.PENDING, null),
        new Valid(PaymentStatus.PROCESSING, PaymentTrigger.PAYMENT_DECLINED, "T3a", PaymentStatus.PENDING, null),
        new Valid(PaymentStatus.PENDING, PaymentTrigger.PAYMENT_FAILED_CONFIRMED, "T3", PaymentStatus.FAILED, FinancialFact.PAYMENT_FAILED),
        new Valid(PaymentStatus.PENDING, PaymentTrigger.PAYMENT_SESSION_EXPIRED, "T4", PaymentStatus.EXPIRED, FinancialFact.PAYMENT_EXPIRED),
        new Valid(PaymentStatus.PROCESSING, PaymentTrigger.PAYMENT_SESSION_EXPIRED, "T4", PaymentStatus.EXPIRED, FinancialFact.PAYMENT_EXPIRED),
        new Valid(PaymentStatus.PENDING, PaymentTrigger.PAYMENT_CANCELED, "T5", PaymentStatus.CANCELED, FinancialFact.PAYMENT_CANCELED),
        new Valid(PaymentStatus.PROCESSING, PaymentTrigger.PAYMENT_CANCELED, "T5", PaymentStatus.CANCELED, FinancialFact.PAYMENT_CANCELED),
        new Valid(PaymentStatus.SUCCESS, PaymentTrigger.REFUND_REQUEST_ACCEPTED, "T6", PaymentStatus.REFUNDING, FinancialFact.REFUND_INITIATED),
        new Valid(PaymentStatus.REFUNDING, PaymentTrigger.REFUND_SUCCESS_CONFIRMED, "T7", PaymentStatus.REFUNDED, FinancialFact.REFUND_SUCCESS),
        new Valid(PaymentStatus.REFUNDING, PaymentTrigger.REFUND_FAILED_CONFIRMED, "T8", PaymentStatus.SUCCESS, FinancialFact.REFUND_FAILED),
        new Valid(PaymentStatus.REFUNDED, PaymentTrigger.REFUND_REVERSED_CONFIRMED, "T9", PaymentStatus.SUCCESS, FinancialFact.REFUND_REVERSED)
    );

    @Test
    void implements_every_documented_transition_and_rejects_every_other_pair() {
        PaymentStateMachine machine = new PaymentStateMachine();
        Set<String> seenIds = new HashSet<>();
        for (PaymentStatus status : PaymentStatus.values()) {
            for (PaymentTrigger trigger : PaymentTrigger.values()) {
                Valid expected = VALID.stream().filter(v -> v.from == status && v.trigger == trigger).findFirst().orElse(null);
                if (expected == null) {
                    assertThatThrownBy(() -> machine.transition(status, trigger))
                            .isInstanceOf(InvalidTransitionException.class);
                } else {
                    PaymentTransition actual = machine.transition(status, trigger);
                    assertThat(actual.transitionId()).isEqualTo(expected.id);
                    assertThat(actual.to()).isEqualTo(expected.to);
                    assertThat(actual.fact().orElse(null)).isEqualTo(expected.fact);
                    seenIds.add(actual.transitionId());
                }
            }
        }
        assertThat(seenIds).containsExactlyInAnyOrder("T0.1","T1","T2","T3a","T3","T4","T5","T6","T7","T8","T9");
    }

    @Test
    void increments_financial_version_only_when_a_fact_is_emitted() {
        PaymentTransaction payment = new PaymentTransaction("TXN_01J9Z3M4R8Q2K7V6W5X1Y0ZABC", 1, 1,
                UUID.randomUUID(), new Money(100, "VND"), Instant.now().plusSeconds(900),
                PaymentStatus.INITIATING, 0);
        payment.apply(new PaymentStateMachine(), PaymentTrigger.PROVIDER_INTENT_IDENTIFIED);
        assertThat(payment.financialStateVersion()).isEqualTo(1);
        payment.apply(new PaymentStateMachine(), PaymentTrigger.PAYMENT_DECLINED);
        assertThat(payment.financialStateVersion()).isEqualTo(1);
        payment.apply(new PaymentStateMachine(), PaymentTrigger.PAYMENT_SUCCESS_CONFIRMED);
        assertThat(payment.financialStateVersion()).isEqualTo(2);
    }
}
