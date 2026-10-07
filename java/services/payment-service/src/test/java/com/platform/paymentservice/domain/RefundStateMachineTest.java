package com.platform.paymentservice.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundStateMachineTest {
    private record Key(RefundStatus status, RefundTrigger trigger) {}
    private static final Map<Key, String> VALID = Map.of(
        new Key(RefundStatus.PENDING, RefundTrigger.REFUND_REQUEST_SENT), "R1",
        new Key(RefundStatus.PROCESSING, RefundTrigger.REFUND_SUCCESS_CONFIRMED), "R2",
        new Key(RefundStatus.PROCESSING, RefundTrigger.REFUND_FAILED_CONFIRMED), "R3",
        new Key(RefundStatus.PENDING, RefundTrigger.REFUND_REQUEST_REJECTED), "R4",
        new Key(RefundStatus.SUCCESS, RefundTrigger.REFUND_REVERSED_CONFIRMED), "R5"
    );

    @Test
    void implements_all_and_only_documented_transitions() {
        RefundStateMachine machine = new RefundStateMachine();
        for (RefundStatus status : RefundStatus.values()) {
            for (RefundTrigger trigger : RefundTrigger.values()) {
                String id = VALID.get(new Key(status, trigger));
                if (id == null) {
                    assertThatThrownBy(() -> machine.transition(status, trigger))
                            .isInstanceOf(InvalidTransitionException.class);
                } else {
                    assertThat(machine.transition(status, trigger).transitionId()).isEqualTo(id);
                }
            }
        }
    }
}
