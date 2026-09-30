package com.platform.paymentservice.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisputeStateMachineTest {
    private final DisputeStateMachine machine = new DisputeStateMachine();

    @Test
    void implements_D1_only_for_dispute_created() {
        assertThat(machine.open(DisputeTrigger.DISPUTE_CREATED).transitionId()).isEqualTo("D1");
        for (DisputeTrigger trigger : DisputeTrigger.values()) {
            if (trigger != DisputeTrigger.DISPUTE_CREATED) {
                assertThatThrownBy(() -> machine.open(trigger)).isInstanceOf(InvalidTransitionException.class);
            }
        }
    }

    @Test
    void implements_all_and_only_documented_existing_dispute_transitions() {
        Set<DisputeStatus> active = Set.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW);
        for (DisputeStatus status : DisputeStatus.values()) {
            for (DisputeFundsState funds : DisputeFundsState.values()) {
                DisputeState current = new DisputeState(status, funds);
                for (DisputeTrigger trigger : DisputeTrigger.values()) {
                    boolean valid = trigger == DisputeTrigger.FUNDS_WITHDRAWN
                            || trigger == DisputeTrigger.FUNDS_REINSTATED
                            || (trigger == DisputeTrigger.DISPUTE_UPDATED && status == DisputeStatus.OPEN)
                            || (active.contains(status) && Set.of(
                                    DisputeTrigger.DISPUTE_CLOSED_WON,
                                    DisputeTrigger.DISPUTE_CLOSED_LOST,
                                    DisputeTrigger.WARNING_CLOSED).contains(trigger));
                    if (!valid) {
                        assertThatThrownBy(() -> machine.transition(current, trigger))
                                .isInstanceOf(InvalidTransitionException.class);
                    } else {
                        assertThat(machine.transition(current, trigger).transitionId())
                                .isIn("D2", "D3", "D4", "D5a", "D5b", "D5c");
                    }
                }
            }
        }
    }

    @Test
    void funds_transitions_do_not_change_dispute_status() {
        for (DisputeStatus status : DisputeStatus.values()) {
            DisputeState current = new DisputeState(status, DisputeFundsState.UNCHANGED);
            assertThat(machine.transition(current, DisputeTrigger.FUNDS_WITHDRAWN).to())
                    .isEqualTo(new DisputeState(status, DisputeFundsState.WITHDRAWN));
            assertThat(machine.transition(current, DisputeTrigger.FUNDS_REINSTATED).to())
                    .isEqualTo(new DisputeState(status, DisputeFundsState.REINSTATED));
        }
    }
}
