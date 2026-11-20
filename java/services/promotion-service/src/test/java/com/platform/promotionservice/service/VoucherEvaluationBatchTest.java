package com.platform.promotionservice.service;

import com.platform.promotionservice.domain.enums.BudgetGuard;
import com.platform.promotionservice.domain.enums.DiscountType;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.enums.EligibilityReason;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import com.platform.promotionservice.domain.enums.ValidityMode;
import com.platform.promotionservice.domain.rule.ConditionNode;
import com.platform.promotionservice.domain.rule.RuleFields;
import com.platform.promotionservice.domain.rule.RuleOperator;
import com.platform.promotionservice.infrastructure.redis.RuntimeStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VoucherEvaluationBatchTest {
    private final PromotionSnapshotService snapshots = mock(PromotionSnapshotService.class);
    private final RuntimeStore runtime = mock(RuntimeStore.class);
    private final VoucherEvaluationService evaluator = new VoucherEvaluationService(snapshots, runtime);
    private final Instant now = Instant.parse("2026-10-10T00:00:00Z");
    private final RuleFactsBuilder.Context context = new RuleFactsBuilder.Context(350000,
            Map.of(RuleFields.CART_TOTAL_AMOUNT, 350000L));

    @Test
    void redisFailureDegradesOnlyRuleEligibleCandidatesAndDoesNotReadIndividually() {
        givenRule();
        var eligible = candidate(1, 0);
        var ineligible = candidate(2, 1000000);
        when(runtime.readAll(List.of(1L))).thenThrow(new IllegalStateException("Redis unavailable"));

        var result = evaluator.evaluateAll(List.of(eligible, ineligible), context, now);
        assertThat(result.get(0).eligible()).isTrue();
        assertThat(result.get(0).discountVnd()).isEqualTo(35000);
        assertThat(result.get(0).budgetGuard()).isEqualTo(BudgetGuard.DEGRADED);
        assertThat(result.get(1).reason()).isEqualTo(EligibilityReason.MIN_ORDER_NOT_MET);
        assertThat(result.get(1).budgetGuard()).isEqualTo(BudgetGuard.NOT_CHECKED);
        verify(runtime).readAll(List.of(1L));
        verifyNoMoreInteractions(runtime);
    }

    @Test
    void emptyOrEntirelyIneligibleBatchDoesNotReadRedis() {
        assertThat(evaluator.evaluateAll(List.of(), context, now)).isEmpty();
        var result = evaluator.evaluateAll(List.of(candidate(1, 1000000)), context, now);
        assertThat(result.get(0).eligible()).isFalse();
        verifyNoInteractions(runtime);
    }

    @Test
    void batchMatchesSingleEvaluationForAvailableExhaustedAndAbsentRuntime() {
        givenRule();
        var available = new RuntimeStore.RuntimeState(10, 1000000, 0, 0, 0, 0, false, 1, 0, 0);
        var exhausted = new RuntimeStore.RuntimeState(1, 1000000, 1, 0, 0, 0, false, 1, 0, 0);
        when(runtime.readAll(List.of(1L, 2L, 3L))).thenReturn(Map.of(1L, available, 2L, exhausted));
        when(runtime.read(1)).thenReturn(Optional.of(available));
        when(runtime.read(2)).thenReturn(Optional.of(exhausted));
        when(runtime.read(3)).thenReturn(Optional.empty());
        var candidates = List.of(candidate(1, 0), candidate(2, 0), candidate(3, 0));
        assertThat(evaluator.evaluateAll(candidates, context, now)).containsExactlyElementsOf(candidates.stream()
                .map(candidate -> evaluator.evaluate(candidate.view(), candidate.wallet(), context, now, true)).toList());
    }

    private void givenRule() {
        when(snapshots.parseRule(anyString())).thenReturn(
                ConditionNode.of(RuleOperator.GREATER_THAN_OR_EQUAL, RuleFields.CART_TOTAL_AMOUNT, 100000));
    }

    static VoucherEvaluationService.Candidate candidate(long id, long minOrder) {
        return new VoucherEvaluationService.Candidate(new PromotionView(id, 1, 1, "Voucher", null,
                PublicationStatus.PUBLISHED, Instant.parse("2026-10-09T00:00:00Z"), Instant.parse("2026-10-11T00:00:00Z"),
                "PROMO-" + id, DistributionMode.PUBLIC_SAVE, DiscountType.PERCENTAGE, 1000, 50000L,
                minOrder, "{}", 1, ValidityMode.CAMPAIGN_END, null, null, true, 0), null);
    }
}
