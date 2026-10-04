package com.ecommerce.inventory.domain;

import java.util.Collection;
import java.util.Set;

/** Refund Scope lifecycle and counter rules (domain.md, "Refund Scope and return lifecycle"). */
public final class RefundScopeStates {

    public enum State { REGISTERED, IN_PROGRESS, INSPECTION_COMPLETED, CANCELLED, CLOSED, CLOSED_INCOMPLETE }

    public static final Set<State> TERMINAL = Set.of(State.CANCELLED, State.CLOSED, State.CLOSED_INCOMPLETE);

    public enum CloseReason { REFUNDED, REJECTED }

    public enum CloseIncompleteReason { RETURN_WINDOW_EXPIRED, PACKAGE_LOST, CUSTOMER_WITHDREW }

    private RefundScopeStates() {
    }

    /** {@code 0 <= passed + failed <= received <= expected}. */
    public record LineCounters(long expected, long received, long passed, long failed) {

        public LineCounters {
            if (passed < 0 || failed < 0 || passed + failed > received || received > expected || expected < 1) {
                throw new Buckets.InvariantViolation("refund scope counters out of range");
            }
        }

        public boolean fullyInspected() {
            return passed + failed == received;
        }

        public boolean complete() {
            return received == expected && fullyInspected();
        }
    }

    /** Every expected unit has a final result: the scope reaches {@code INSPECTION_COMPLETED}. */
    public static boolean allInspected(Collection<LineCounters> lines) {
        return lines.stream().allMatch(LineCounters::complete);
    }

    /** Guard of {@code close-incomplete}: all received final-inspected and something missing. */
    public static boolean incompleteClosable(Collection<LineCounters> lines) {
        return lines.stream().allMatch(LineCounters::fullyInspected)
                && lines.stream().anyMatch(line -> line.received() < line.expected());
    }
}
