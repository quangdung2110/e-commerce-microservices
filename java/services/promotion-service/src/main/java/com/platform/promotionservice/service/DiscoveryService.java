package com.platform.promotionservice.service;

import com.platform.promotionservice.common.Ids;
import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.domain.entity.UserCouponWalletEntity;
import com.platform.promotionservice.domain.enums.BudgetGuard;
import com.platform.promotionservice.domain.enums.EligibilityReason;
import com.platform.promotionservice.domain.enums.VoucherSource;
import com.platform.promotionservice.domain.enums.WalletStatus;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import com.platform.promotionservice.infrastructure.persistence.PromotionRepository;
import com.platform.promotionservice.infrastructure.persistence.UserCouponWalletRepository;
import com.platform.promotionservice.web.dto.CheckoutDtos;
import com.platform.promotionservice.web.dto.DiscountDto;
import com.platform.promotionservice.web.dto.MoneyDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * WF-PROMO-004: wallet tray, public discovery và calculate. Read path đồng bộ; không
 * save, không reserve. Ranking deterministic: eligible trước, discount giảm dần,
 * promotion_id tăng dần.
 */
@Service
@RequiredArgsConstructor
public class DiscoveryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int OPTIONS_DEFAULT_LIMIT = 50;

    private final UserCouponWalletRepository wallets;
    private final PromotionRepository promotions;
    private final PromotionJdbc jdbc;
    private final PromotionSnapshotService snapshots;
    private final VoucherEvaluationService evaluator;
    private final Clock clock;
    private final PromotionProperties properties;

    /** {@code validUntil}: wallet expiry for a WALLET voucher, campaign end for a PUBLIC one. */
    private record Ranked(CheckoutDtos.VoucherEvaluation dto, long promotionId, PromotionView view, Instant validUntil) {
    }

    private static final Comparator<Ranked> ORDER = Comparator
            .comparing((Ranked r) -> !r.dto().eligible())
            .thenComparing(Comparator.comparingLong((Ranked r) -> r.dto().discount().amountMinor()).reversed())
            .thenComparingLong(Ranked::promotionId);

    /** Worst retained item at the head; both wallet and public share a global top K. */
    private static final class TopK {
        private final int limit;
        private final boolean includeIneligible;
        private final PriorityQueue<Ranked> items = new PriorityQueue<>(ORDER.reversed());

        private TopK(int limit, boolean includeIneligible) {
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be positive");
            }
            this.limit = limit;
            this.includeIneligible = includeIneligible;
        }

        private void add(Ranked item) {
            if (!includeIneligible && !item.dto().eligible()) {
                return;
            }
            if (items.size() < limit) {
                items.add(item);
            } else if (ORDER.compare(item, items.peek()) < 0) {
                items.poll();
                items.add(item);
            }
        }

        private List<Ranked> sorted() {
            return items.stream().sorted(ORDER).toList();
        }
    }

    @Transactional
    public CheckoutDtos.VoucherSuggestionResponse suggest(UUID userId, CheckoutDtos.SuggestionRequest request) {
        Instant now = clock.instant();
        RuleFactsBuilder.Context context = RuleFactsBuilder.build(request.context(), userId, null);
        TopK ranked = new TopK(limitOf(request.limit()), true);
        evaluateWallet(ranked, userId, context, now);
        // Wallet tray trả cả voucher không đủ điều kiện kèm reason (openapi: "cùng eligibility hiện tại").
        return respond(ranked, now);
    }

    @Transactional
    public CheckoutDtos.VoucherSuggestionResponse discover(UUID userId, CheckoutDtos.DiscoveryRequest request) {
        Instant now = clock.instant();
        RuleFactsBuilder.Context context = RuleFactsBuilder.build(request.context(), userId, null);
        TopK ranked = new TopK(limitOf(request.limit()), Boolean.TRUE.equals(request.includeIneligible()));
        evaluateWallet(ranked, userId, context, now);
        addPublic(ranked, userId, context, now);
        return respond(ranked, now);
    }

    /**
     * Voucher tray of the checkout page (Promotion ADR-018, Checkout ADR-017): wallet and public vouchers
     * evaluated on the trusted context Checkout built from its own quotation. Same logic and ranking as
     * suggest/discover, plus the display data of each voucher. A safe query apart from the lazy
     * normalization of expired SAVED vouchers: it saves, claims and reserves nothing.
     */
    @Transactional
    public CheckoutDtos.VoucherOptionList options(CheckoutDtos.VoucherOptionRequest request) {
        RuleFactsBuilder.requireCheckoutChannel(request.context());
        Instant now = clock.instant();
        RuleFactsBuilder.Context context = RuleFactsBuilder.build(request.context(), request.userId(), request.userTier());
        TopK ranked = new TopK(request.limit() == null ? OPTIONS_DEFAULT_LIMIT : request.limit(),
                !Boolean.FALSE.equals(request.includeIneligible()));
        evaluateWallet(ranked, request.userId(), context, now);
        if (!Boolean.FALSE.equals(request.includePublic())) {
            addPublic(ranked, request.userId(), context, now);
        }
        List<CheckoutDtos.VoucherOption> items = ranked.sorted().stream()
                .map(item -> new CheckoutDtos.VoucherOption(item.dto(), display(item)))
                .toList();
        return new CheckoutDtos.VoucherOptionList(items, now);
    }

    private static CheckoutDtos.VoucherDisplay display(Ranked item) {
        PromotionView view = item.view();
        // A snapshot cached before the name was added has none: the code still identifies the voucher.
        String name = view.name() == null || view.name().isBlank() ? view.code() : view.name();
        return new CheckoutDtos.VoucherDisplay(view.code(), name, view.description(),
                DiscountDto.fromStorage(view.discountType(), view.discountValue(), view.maxDiscountVnd()),
                MoneyDto.vnd(view.minOrderAmountVnd()), item.validUntil());
    }

    /** Active public campaigns the user does not already own, evaluated without a wallet. */
    private void addPublic(TopK ranked, UUID userId, RuleFactsBuilder.Context context, Instant now) {
        long afterId = 0;
        int batchSize = properties.getDiscovery().getBatchSize();
        while (true) {
            List<Long> ids = promotions.findPublicActiveAfter(userId, now, afterId, PageRequest.of(0, batchSize));
            if (ids.isEmpty()) {
                return;
            }
            Map<Long, PromotionView> views = snapshots.findAll(ids);
            List<VoucherEvaluationService.Candidate> candidates = ids.stream().filter(views::containsKey)
                    .map(id -> new VoucherEvaluationService.Candidate(views.get(id), null)).toList();
            for (VoucherEvaluationService.Evaluation evaluation : evaluator.evaluateAll(candidates, context, now)) {
                PromotionView view = evaluation.view();
                ranked.add(new Ranked(evaluator.toDto(evaluation, VoucherSource.PUBLIC, null), view.promotionId(), view,
                        view.endTime()));
            }
            // Advance using the scanned IDs, even when snapshots are absent or all rules reject.
            afterId = ids.get(ids.size() - 1);
            if (ids.size() < batchSize) {
                return;
            }
        }
    }

    /** Checkout tính lại 0/1 voucher từ trusted context; pure/replay-safe. */
    @Transactional(readOnly = true)
    public CheckoutDtos.DiscountCalculationResult calculate(CheckoutDtos.DiscountCalculationRequest request) {
        RuleFactsBuilder.requireCheckoutChannel(request.context());
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
    private void evaluateWallet(TopK ranked, UUID userId, RuleFactsBuilder.Context context, Instant now) {
        jdbc.lazyExpireWallets(userId, now);
        long afterId = 0;
        int batchSize = properties.getDiscovery().getBatchSize();
        while (true) {
            List<UserCouponWalletRepository.SavedCandidate> page = wallets.findSavedAfter(userId, afterId,
                    PageRequest.of(0, batchSize));
            if (page.isEmpty()) {
                return;
            }
            Map<Long, PromotionView> views = snapshots.findAll(page.stream()
                    .map(UserCouponWalletRepository.SavedCandidate::getPromotionId).toList());
            List<VoucherEvaluationService.Candidate> candidates = new ArrayList<>();
            for (UserCouponWalletRepository.SavedCandidate row : page) {
                PromotionView view = views.get(row.getPromotionId());
                if (view != null) {
                    UserCouponWalletEntity wallet = new UserCouponWalletEntity();
                    wallet.setId(row.getId());
                    wallet.setPromotionId(row.getPromotionId());
                    wallet.setExpiredAt(row.getExpiredAt());
                    wallet.setStatus(WalletStatus.SAVED);
                    candidates.add(new VoucherEvaluationService.Candidate(view, wallet));
                }
            }
            List<VoucherEvaluationService.Evaluation> evaluations = evaluator.evaluateAll(candidates, context, now);
            for (int i = 0; i < candidates.size(); i++) {
                UserCouponWalletEntity wallet = candidates.get(i).wallet();
                PromotionView view = candidates.get(i).view();
                VoucherEvaluationService.Evaluation evaluation = evaluations.get(i);
                ranked.add(new Ranked(evaluator.toDto(evaluation, VoucherSource.WALLET, wallet.getId()),
                        wallet.getPromotionId(), view, wallet.getExpiredAt()));
            }
            afterId = page.get(page.size() - 1).getId();
            if (page.size() < batchSize) {
                return;
            }
        }
    }

    private static CheckoutDtos.VoucherSuggestionResponse respond(TopK ranked, Instant now) {
        List<CheckoutDtos.VoucherEvaluation> items = ranked.sorted().stream()
                .map(Ranked::dto)
                .toList();
        return new CheckoutDtos.VoucherSuggestionResponse(items, now);
    }

    private static int limitOf(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : limit;
    }
}
