package com.ecommerce.inventory.domain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/** Line and root reservation states (domain.md, "Reservation lifecycle"). */
public final class ReservationStates {

    public enum LineState { RESERVED, COMMITTED, DISPATCHED, RELEASED }

    public enum RootState { RESERVED, COMMITTED, PARTIALLY_DISPATCHED, DISPATCHED, RELEASED }

    public enum ReleaseReason {
        PAYMENT_FAILED, RESERVATION_EXPIRED, BUYER_CANCELLED, ADMIN_CANCELLED, PRE_PAYMENT_SAGA_FAILED
    }

    public static final String COMMITTED_RELEASE_REASON = "ADMIN_FORCE_REFUND_BEFORE_DISPATCH";

    private ReservationStates() {
    }

    /**
     * Derives the root state from its lines. Every release is whole-root, so a root never
     * mixes released lines with committed or dispatched ones; such a mix is an invariant
     * violation.
     */
    public static RootState derive(Collection<LineState> lines) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("a reservation root has at least one line");
        }
        Set<LineState> states = EnumSet.copyOf(lines);
        if (states.size() == 1) {
            return switch (states.iterator().next()) {
                case RESERVED -> RootState.RESERVED;
                case COMMITTED -> RootState.COMMITTED;
                case DISPATCHED -> RootState.DISPATCHED;
                case RELEASED -> RootState.RELEASED;
            };
        }
        if (states.equals(EnumSet.of(LineState.COMMITTED, LineState.DISPATCHED))) {
            return RootState.PARTIALLY_DISPATCHED;
        }
        throw new Buckets.InvariantViolation("mixed reservation line states " + states);
    }
}
