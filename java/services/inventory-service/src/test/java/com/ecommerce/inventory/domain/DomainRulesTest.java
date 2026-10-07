package com.ecommerce.inventory.domain;

import com.ecommerce.inventory.domain.ReservationStates.LineState;
import com.ecommerce.inventory.domain.ReservationStates.RootState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.LongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainRulesTest {

    private static final List<LongFunction<BucketDelta>> TRANSITIONS = List.of(
            BucketDelta::sellableIn, BucketDelta::sellableOut, BucketDelta::reserve, BucketDelta::releaseReservation,
            BucketDelta::commitPayment, BucketDelta::recoverToCommitted, BucketDelta::releaseCommitted,
            BucketDelta::physicalDispatch, BucketDelta::returnReceipt, BucketDelta::inspectionPass,
            BucketDelta::quarantineOut, BucketDelta::quarantineBack);

    @Test
    void everyTransitionOfTheTableIsBalanced() {
        TRANSITIONS.forEach(t -> {
            BucketDelta d = t.apply(3);
            assertThat(d.availableToSell() + d.reserved() + d.committed() + d.quarantine()).isEqualTo(d.onHand());
        });
    }

    @Test
    void randomWalkNeverBreaksTheBucketInvariant() {
        Random random = new Random(42);
        Buckets buckets = Buckets.EMPTY;
        for (int i = 0; i < 20_000; i++) {
            BucketDelta delta = TRANSITIONS.get(random.nextInt(TRANSITIONS.size())).apply(1 + random.nextInt(5));
            if (buckets.permits(delta)) {
                buckets = buckets.apply(delta);
            } else {
                Buckets before = buckets;
                assertThatThrownBy(() -> before.apply(delta)).isInstanceOf(Buckets.InvariantViolation.class);
            }
            assertThat(buckets.availableToSell() + buckets.reserved() + buckets.committed() + buckets.quarantine())
                    .isEqualTo(buckets.onHand());
        }
    }

    @Test
    void negativeAdjustmentCannotTakeReservedOrQuarantinedUnits() {
        Buckets buckets = new Buckets(10, 1, 4, 3, 2);
        assertThat(buckets.permits(BucketDelta.sellableOut(2))).isFalse();
        assertThat(buckets.permits(BucketDelta.sellableOut(1))).isTrue();
    }

    @Test
    void unbalancedDeltaIsRefused() {
        assertThatThrownBy(() -> new BucketDelta(1, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rootStateIsDerivedFromLines() {
        assertThat(ReservationStates.derive(List.of(LineState.RESERVED, LineState.RESERVED))).isEqualTo(RootState.RESERVED);
        assertThat(ReservationStates.derive(List.of(LineState.COMMITTED, LineState.DISPATCHED)))
                .isEqualTo(RootState.PARTIALLY_DISPATCHED);
        assertThat(ReservationStates.derive(List.of(LineState.DISPATCHED))).isEqualTo(RootState.DISPATCHED);
        assertThatThrownBy(() -> ReservationStates.derive(List.of(LineState.RELEASED, LineState.COMMITTED)))
                .isInstanceOf(Buckets.InvariantViolation.class);
    }

    @Test
    void refundScopeCountersAndClosureGuards() {
        RefundScopeStates.LineCounters done = new RefundScopeStates.LineCounters(2, 2, 1, 1);
        RefundScopeStates.LineCounters missing = new RefundScopeStates.LineCounters(2, 1, 1, 0);
        RefundScopeStates.LineCounters pending = new RefundScopeStates.LineCounters(2, 2, 1, 0);
        assertThat(RefundScopeStates.allInspected(List.of(done))).isTrue();
        assertThat(RefundScopeStates.allInspected(List.of(done, missing))).isFalse();
        assertThat(RefundScopeStates.incompleteClosable(List.of(done, missing))).isTrue();
        assertThat(RefundScopeStates.incompleteClosable(List.of(done, pending))).isFalse();
        assertThatThrownBy(() -> new RefundScopeStates.LineCounters(2, 3, 0, 0))
                .isInstanceOf(Buckets.InvariantViolation.class);
        assertThatThrownBy(() -> new RefundScopeStates.LineCounters(2, 1, 1, 1))
                .isInstanceOf(Buckets.InvariantViolation.class);
    }

    @Test
    void allocationPicksPriorityThenWarehouseIdAndNeverSplitsOrDoublePromises() {
        Map<String, List<AllocationPolicy.Candidate>> candidates = Map.of("sku", List.of(
                new AllocationPolicy.Candidate("wh-b", 1, true, false, 5),
                new AllocationPolicy.Candidate("wh-a", 1, true, false, 3),
                new AllocationPolicy.Candidate("wh-0", 0, true, true, 100),
                new AllocationPolicy.Candidate("wh-c", 0, false, false, 100)));
        List<Optional<AllocationPolicy.Choice>> plan = AllocationPolicy.plan(List.of(
                new AllocationPolicy.Line("l1", "sku", 3),
                new AllocationPolicy.Line("l2", "sku", 4),
                new AllocationPolicy.Line("l3", "sku", 2)), candidates);
        assertThat(plan.get(0)).get().extracting(c -> c.warehouse().warehouseId()).isEqualTo("wh-a");
        assertThat(plan.get(1)).get().extracting(c -> c.warehouse().warehouseId()).isEqualTo("wh-b");
        assertThat(plan.get(2)).isEmpty();
    }
}
