package com.platform.promotionservice.service;

import com.platform.promotionservice.common.Ids;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.entity.UserCouponWalletEntity;
import com.platform.promotionservice.domain.enums.BudgetGuard;
import com.platform.promotionservice.domain.enums.EligibilityReason;
import com.platform.promotionservice.domain.enums.VoucherSource;
import com.platform.promotionservice.domain.enums.WalletStatus;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import com.platform.promotionservice.infrastructure.persistence.PromotionRepository;
import com.platform.promotionservice.infrastructure.persistence.UserCouponWalletRepository;
import com.platform.promotionservice.web.dto.CheckoutDtos;
import com.platform.promotionservice.web.dto.MoneyDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * WF-PROMO-004: wallet tray, public discovery và calculate. Read path đồng bộ; không
 * save, không reserve. Ranking deterministic: eligible trước, discount giảm dần,
 * promotion_id tăng dần.
 */
@Service
@RequiredArgsConstructor
public class DiscoveryService {

    private static final int PUBLIC_CANDIDATE_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 20;

    private final UserCouponWalletRepository wallets;
    private final PromotionRepository promotions;
    private final PromotionJdbc jdbc;
    private final PromotionSnapshotService snapshots;
    private final VoucherEvaluationService evaluator;
    private final Clock clock;

    private record Ranked(CheckoutDtos.VoucherEvaluation dto, long promotionId) {
    }

    @Transactional
    public CheckoutDtos.VoucherSuggestionResponse suggest(UUID userId, CheckoutDtos.SuggestionRequest request) {
        Instant now = clock.instant();
        RuleFactsBuilder.Context context = RuleFactsBuilder.build(request.context(), userId, null);
        List<Ranked> ranked = evaluateWallet(userId, context, now);
        // Wallet tray trả cả voucher không đủ điều kiện kèm reason (openapi: "cùng eligibility hiện tại").
        return respond(ranked, limitOf(request.limit()), true, now);
    }

    @Transactional
    public CheckoutDtos.VoucherSuggestionResponse discover(UUID userId, CheckoutDtos.DiscoveryRequest request) {
        Instant now = clock.instant();
        RuleFactsBuilder.Context context = RuleFactsBuilder.build(request.context(), userId, null);
        List<Ranked> ranked = new ArrayList<>(evaluateWallet(userId, context, now));
        Set<Long> owned = new HashSet<>();
        ranked.forEach(item -> owned.add(item.promotionId()));
        wallets.findByUserIdAndStatus(userId, WalletStatus.USED).forEach(w -> owned.add(w.getPromotionId()));
        wallets.findByUserIdAndStatus(userId, WalletStatus.EXPIRED).forEach(w -> owned.add(w.getPromotionId()));

        for (PromotionEntity promotion : promotions.findPublicActive(now, PageRequest.of(0, PUBLIC_CANDIDATE_LIMIT))) {
            if (owned.contains(promotion.getId())) {
                continue;
            }
            snapshots.find(promotion.getId()).ifPresent(view -> {
                VoucherEvaluationService.Evaluation evaluation = evaluator.evaluate(view, null, context, now, true);
                ranked.add(new Ranked(evaluator.toDto(evaluation, VoucherSource.PUBLIC, null), promotion.getId()));
            });
        }
        return respond(ranked, limitOf(request.limit()), Boolean.TRUE.equals(request.includeIneligible()), now);
    }

    /** Checkout tính lại 0/1 voucher từ trusted context; pure/replay-safe. */
    @Transactional(readOnly = true)
    public CheckoutDtos.DiscountCalculationResult calculate(CheckoutDtos.DiscountCalculationRequest request) {
        Instant now = clock.instant();
        RuleFactsBuilder.Context context = RuleFactsBuilder.build(request.context(), request.userId(), request.userTier());
        MoneyDto subtotal = MoneyDto.vnd(context.subtotalVnd());
        if (request.promotionId() == null) {
            return new CheckoutDtos.DiscountCalculationResult(null, false, EligibilityReason.NO_VOUCHER_SELECTED,
                    subtotal, MoneyDto.vnd(0), subtotal, null, null, BudgetGuard.NOT_CHECKED, now);
        }
        long promotionId = Ids.parse(request.promotionId(), "promotion_id");
        Optional<PromotionView> view = snapshots.find(promotionId);
        if (view.isEmpty()) {
            return new CheckoutDtos.DiscountCalculationResult(request.promotionId(), false,
                    EligibilityReason.VOUCHER_NOT_OWNED, subtotal, MoneyDto.vnd(0), subtotal, null, null,
                    BudgetGuard.NOT_CHECKED, now);
        }
        Optional<UserCouponWalletEntity> wallet = wallets.findByUserIdAndPromotionId(request.userId(), promotionId);
        if (wallet.isEmpty()) {
            return new CheckoutDtos.DiscountCalculationResult(request.promotionId(), false,
                    EligibilityReason.VOUCHER_NOT_OWNED, subtotal, MoneyDto.vnd(0), subtotal,
                    view.get().ruleSchemaVersion(), view.get().campaignRevision(), BudgetGuard.NOT_CHECKED, now);
        }
        VoucherEvaluationService.Evaluation evaluation = evaluator.evaluate(view.get(), wallet.get(), context, now, true);
        return new CheckoutDtos.DiscountCalculationResult(request.promotionId(), evaluation.eligible(),
                evaluation.reason(), subtotal, MoneyDto.vnd(evaluation.discountVnd()),
                MoneyDto.vnd(evaluation.finalAmountVnd()), view.get().ruleSchemaVersion(),
                view.get().campaignRevision(), evaluation.budgetGuard(), now);
    }

    /** Lazy-normalize SAVED đã hết hạn rồi evaluate từng voucher SAVED còn lại. */
    private List<Ranked> evaluateWallet(UUID userId, RuleFactsBuilder.Context context, Instant now) {
        jdbc.lazyExpireWallets(userId, now);
        List<Ranked> ranked = new ArrayList<>();
        for (UserCouponWalletEntity wallet : wallets.findByUserIdAndStatus(userId, WalletStatus.SAVED)) {
            snapshots.find(wallet.getPromotionId()).ifPresent(view -> {
                VoucherEvaluationService.Evaluation evaluation = evaluator.evaluate(view, wallet, context, now, true);
                ranked.add(new Ranked(evaluator.toDto(evaluation, VoucherSource.WALLET, wallet.getId()),
                        wallet.getPromotionId()));
            });
        }
        return ranked;
    }

    private static CheckoutDtos.VoucherSuggestionResponse respond(List<Ranked> ranked, int limit,
                                                                  boolean includeIneligible, Instant now) {
        Comparator<Ranked> order = Comparator
                .comparing((Ranked r) -> !r.dto().eligible())
                .thenComparing(r -> -r.dto().discount().amountMinor())
                .thenComparingLong(Ranked::promotionId);
        List<CheckoutDtos.VoucherEvaluation> items = ranked.stream()
                .filter(r -> includeIneligible || r.dto().eligible())
                .sorted(order)
                .limit(limit)
                .map(Ranked::dto)
                .toList();
        return new CheckoutDtos.VoucherSuggestionResponse(items, now);
    }

    private static int limitOf(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : limit;
    }
}
