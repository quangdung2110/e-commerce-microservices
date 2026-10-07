package com.platform.promotionservice.service;

import com.platform.promotionservice.domain.entity.UserCouponWalletEntity;
import com.platform.promotionservice.domain.enums.BudgetGuard;
import com.platform.promotionservice.domain.enums.CampaignPhase;
import com.platform.promotionservice.domain.enums.EligibilityReason;
import com.platform.promotionservice.domain.enums.VoucherSource;
import com.platform.promotionservice.domain.enums.WalletStatus;
import com.platform.promotionservice.domain.pricing.DiscountMath;
import com.platform.promotionservice.domain.rule.RuleEvaluator;
import com.platform.promotionservice.infrastructure.redis.RuntimeStore;
import com.platform.promotionservice.web.dto.CheckoutDtos;
import com.platform.promotionservice.web.dto.MoneyDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Eligibility + discount toàn đơn cho một promotion (WF-PROMO-004). Wallet voucher
 * chỉ dùng {@code expired_at} đã snapshot, không dùng campaign status làm revocation
 * (PROMO-BR-007); public voucher phải thuộc campaign ACTIVE. Budget guard read-time
 * best-effort: timeout fail-open với marker DEGRADED, Reserve mới là quyết định cuối.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoucherEvaluationService {

    private final PromotionSnapshotService snapshots;
    private final RuntimeStore runtime;

    public record Evaluation(PromotionView view, boolean eligible, EligibilityReason reason,
                             long discountVnd, long finalAmountVnd, BudgetGuard budgetGuard) {
    }

    public Evaluation evaluate(PromotionView view, UserCouponWalletEntity wallet,
                               RuleFactsBuilder.Context context, Instant now, boolean checkBudget) {
        EligibilityReason reason = eligibilityReason(view, wallet, context, now);
        if (reason != EligibilityReason.ELIGIBLE) {
            return ineligible(view, reason, context.subtotalVnd(), BudgetGuard.NOT_CHECKED);
        }
        long discount = DiscountMath.discount(view.discountType(), view.discountValue(), view.maxDiscountVnd(),
                context.subtotalVnd());
        BudgetGuard guard = checkBudget ? budgetGuard(view.promotionId(), discount) : BudgetGuard.NOT_CHECKED;
        if (guard == BudgetGuard.EXHAUSTED) {
            return ineligible(view, EligibilityReason.BUDGET_EXHAUSTED, context.subtotalVnd(), guard);
        }
        return new Evaluation(view, true, EligibilityReason.ELIGIBLE, discount,
                DiscountMath.finalAmount(context.subtotalVnd(), discount), guard);
    }

    private EligibilityReason eligibilityReason(PromotionView view, UserCouponWalletEntity wallet,
                                                RuleFactsBuilder.Context context, Instant now) {
        if (wallet != null) {
            if (wallet.getStatus() == WalletStatus.USED) {
                return EligibilityReason.ALREADY_USED_VOUCHER;
            }
            if (wallet.getStatus() == WalletStatus.EXPIRED || wallet.isExpiredAt(now)) {
                return EligibilityReason.VOUCHER_EXPIRED;
            }
        } else if (view.phaseAt(now) != CampaignPhase.ACTIVE) {
            return EligibilityReason.CAMPAIGN_NOT_ACTIVE;
        }
        if (!view.enabled()) {
            return EligibilityReason.PROMOTION_DISABLED;
        }
        if (context.subtotalVnd() < view.minOrderAmountVnd()) {
            return EligibilityReason.MIN_ORDER_NOT_MET;
        }
        if (!RuleEvaluator.evaluate(snapshots.parseRule(view.conditionsRule()), context.facts())) {
            return EligibilityReason.CONDITIONS_NOT_MET;
        }
        return EligibilityReason.ELIGIBLE;
    }

    private BudgetGuard budgetGuard(long promotionId, long discountVnd) {
        try {
            Optional<RuntimeStore.RuntimeState> state = runtime.read(promotionId);
            if (state.isEmpty()) {
                return BudgetGuard.NOT_CHECKED;
            }
            if (state.get().quantityExhausted() || state.get().budgetExhaustedFor(discountVnd)) {
                return BudgetGuard.EXHAUSTED;
            }
            return BudgetGuard.AVAILABLE;
        } catch (RuntimeException e) {
            log.debug("Budget guard degraded for promotion {}: {}", promotionId, e.getMessage());
            return BudgetGuard.DEGRADED;
        }
    }

    private static Evaluation ineligible(PromotionView view, EligibilityReason reason, long subtotal,
                                         BudgetGuard guard) {
        return new Evaluation(view, false, reason, 0, subtotal, guard);
    }

    public CheckoutDtos.VoucherEvaluation toDto(Evaluation evaluation, VoucherSource source, Long walletId) {
        return new CheckoutDtos.VoucherEvaluation(
                Long.toString(evaluation.view().promotionId()),
                walletId == null ? null : Long.toString(walletId),
                source,
                evaluation.view().distributionMode(),
                evaluation.eligible(),
                evaluation.reason(),
                MoneyDto.vnd(evaluation.discountVnd()),
                MoneyDto.vnd(evaluation.finalAmountVnd()),
                evaluation.view().ruleSchemaVersion(),
                evaluation.view().campaignRevision(),
                evaluation.budgetGuard());
    }
}
