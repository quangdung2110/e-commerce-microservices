package com.platform.paymentservice.domain;

import java.util.Optional;

public final class DisputeStateMachine {
    public DisputeTransition open(DisputeTrigger trigger) {
        if (trigger != DisputeTrigger.DISPUTE_CREATED) throw new InvalidTransitionException(null, trigger);
        DisputeState next = new DisputeState(DisputeStatus.OPEN, DisputeFundsState.UNCHANGED);
        return new DisputeTransition("D1", null, next, Optional.of(FinancialFact.DISPUTE_OPENED));
    }

    public DisputeTransition transition(DisputeState current, DisputeTrigger trigger) {
        return switch (trigger) {
            case DISPUTE_CREATED -> throw new InvalidTransitionException(current, trigger);
            case DISPUTE_UPDATED -> {
                requireOpen(current, trigger);
                yield result("D2", current, new DisputeState(DisputeStatus.UNDER_REVIEW, current.fundsState()), null);
            }
            case FUNDS_WITHDRAWN -> result("D3", current,
                    new DisputeState(current.status(), DisputeFundsState.WITHDRAWN),
                    FinancialFact.DISPUTE_FUNDS_WITHDRAWN);
            case FUNDS_REINSTATED -> result("D4", current,
                    new DisputeState(current.status(), DisputeFundsState.REINSTATED),
                    FinancialFact.DISPUTE_FUNDS_REINSTATED);
            case DISPUTE_CLOSED_WON -> {
                requireActive(current, trigger);
                yield result("D5a", current, new DisputeState(DisputeStatus.WON, current.fundsState()),
                        FinancialFact.DISPUTE_WON);
            }
            case DISPUTE_CLOSED_LOST -> {
                requireActive(current, trigger);
                yield result("D5b", current, new DisputeState(DisputeStatus.LOST, current.fundsState()),
                        FinancialFact.DISPUTE_LOST);
            }
            case WARNING_CLOSED -> {
                requireActive(current, trigger);
                yield result("D5c", current,
                        new DisputeState(DisputeStatus.WARNING_CLOSED, current.fundsState()), null);
            }
        };
    }

    private static void requireActive(DisputeState current, DisputeTrigger trigger) {
        if (current.status() != DisputeStatus.OPEN && current.status() != DisputeStatus.UNDER_REVIEW) {
            throw new InvalidTransitionException(current, trigger);
        }
    }

    private static void requireOpen(DisputeState current, DisputeTrigger trigger) {
        if (current.status() != DisputeStatus.OPEN) {
            throw new InvalidTransitionException(current, trigger);
        }
    }

    private static DisputeTransition result(String id, DisputeState from, DisputeState to, FinancialFact fact) {
        return new DisputeTransition(id, from, to, Optional.ofNullable(fact));
    }
}
