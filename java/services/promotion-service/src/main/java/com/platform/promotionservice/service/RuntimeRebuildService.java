package com.platform.promotionservice.service;

import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import com.platform.promotionservice.domain.entity.PromotionBudgetEntity;
import com.platform.promotionservice.domain.entity.PromotionEntity;
import com.platform.promotionservice.domain.enums.DistributionMode;
import com.platform.promotionservice.domain.enums.PublicationStatus;
import com.platform.promotionservice.infrastructure.persistence.CampaignRepository;
import com.platform.promotionservice.infrastructure.persistence.CouponClaimRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionBudgetRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import com.platform.promotionservice.infrastructure.persistence.PromotionRepository;
import com.platform.promotionservice.infrastructure.redis.ClaimStore;
import com.platform.promotionservice.infrastructure.redis.ReconcileLock;
import com.platform.promotionservice.infrastructure.redis.ReservationStore;
import com.platform.promotionservice.infrastructure.redis.RuntimeStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Rebuild và reconciliation DB → Redis một chiều (data.md §8, WF-PROMO-008).
 * Expected used lấy từ wallet USED, claimed từ claim rows, reserved từ payload còn
 * hiệu lực; không bao giờ ghi PostgreSQL từ counter Redis (PROMO-INV-008).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuntimeRebuildService {

    private static final int PAGE = 500;

    private final PromotionRepository promotions;
    private final CampaignRepository campaigns;
    private final PromotionBudgetRepository budgets;
    private final CouponClaimRepository claims;
    private final PromotionJdbc jdbc;
    private final RuntimeStore runtime;
    private final ClaimStore claimStore;
    private final ReservationStore reservations;
    private final ReconcileLock reconcileLock;
    private final PromotionProperties properties;
    private final Clock clock;

    /** Startup/failover: rebuild runtime claim fields + pool + budget projection cho mọi promotion non-DRAFT. */
    @Transactional(readOnly = true)
    public int rebuildAll() {
        int count = 0;
        long after = 0;
        while (true) {
            List<Long> ids = promotions.runtimeIdsAfter(after, PageRequest.of(0, PAGE));
            if (ids.isEmpty()) {
                return count;
            }
            for (Long id : ids) {
                rebuildPromotion(id);
                count++;
                after = id;
            }
        }
    }

    /** Rebuild runtime hash (budget projection + claim gate) và pool nếu thiếu. */
    @Transactional(readOnly = true)
    public void rebuildPromotion(long promotionId) {
        Optional<PromotionEntity> promotion = promotions.findById(promotionId);
        if (promotion.isEmpty()) {
            return;
        }
        CampaignEntity campaign = campaigns.findById(promotion.get().getCampaignId()).orElseThrow();
        PromotionBudgetEntity budget = budgets.findById(promotionId).orElseThrow();
        writeBudgetProjection(promotionId, budget, null, null);
        writeClaimGate(promotion.get(), campaign);
        if (promotion.get().getDistributionMode() == DistributionMode.LIMITED_CLAIM
                && claimStore.poolSize(promotionId) == 0) {
            rebuildPool(promotionId);
        }
    }

    /** Claim-gate fields từ PostgreSQL: enabled chỉ khi PUBLISHED + promotion enabled + LIMITED_CLAIM. */
    public void writeClaimGate(PromotionEntity promotion, CampaignEntity campaign) {
        boolean enabled = campaign.getPublicationStatus() == PublicationStatus.PUBLISHED
                && promotion.isEnabled()
                && promotion.getDistributionMode() == DistributionMode.LIMITED_CLAIM;
        runtime.writeClaimGate(promotion.getId(), enabled, campaign.getRevision(),
                campaign.getStartTime().toEpochMilli(), campaign.getEndTime().toEpochMilli());
    }

    /** Pool = AVAILABLE claim rows trừ những suất đang pending (chưa DB ack). */
    public void rebuildPool(long promotionId) {
        Set<Object> pending = claimStore.pendingClaimIds(promotionId);
        claimStore.resetPool(promotionId);
        long after = 0;
        int batch = properties.getClaim().getPoolBatchSize();
        while (true) {
            List<Long> ids = claims.availableIdsAfter(promotionId, after, PageRequest.of(0, batch));
            if (ids.isEmpty()) {
                return;
            }
            List<Long> members = new ArrayList<>(ids.size());
            for (Long id : ids) {
                if (!pending.contains(Long.toString(id))) {
                    members.add(id);
                }
            }
            claimStore.addToPool(promotionId, members);
            after = ids.get(ids.size() - 1);
        }
    }

    /**
     * DB-first reconciliation: expected used từ wallet USED, reserved từ payload còn tồn
     * tại; repair Redis một chiều dưới reconcile_lock. Trả false nếu không lấy được lock.
     */
    @Transactional(readOnly = true)
    public boolean reconcile(long promotionId) {
        String token = reconcileLock.tryLock(promotionId, properties.getReconciliation().getLockTtl());
        if (token == null) {
            return false;
        }
        try {
            Optional<PromotionBudgetEntity> budget = budgets.findById(promotionId);
            if (budget.isEmpty()) {
                return true;
            }
            long reservedQty = 0;
            long reservedVnd = 0;
            for (Long orderId : reservations.indexedOrders(promotionId)) {
                Optional<ReservationStore.ReservationPayload> payload = reservations.read(promotionId, orderId);
                if (payload.isEmpty()) {
                    reservations.removeIndex(promotionId, orderId);
                    continue;
                }
                reservedQty++;
                reservedVnd += payload.get().amount();
            }
            writeBudgetProjection(promotionId, budget.get(), reservedQty, reservedVnd);
            log.info("Reconciled promotion {} runtime: reserved_qty={} reserved_vnd={}", promotionId,
                    reservedQty, reservedVnd);
            return true;
        } finally {
            reconcileLock.unlock(promotionId, token);
        }
    }

    private void writeBudgetProjection(long promotionId, PromotionBudgetEntity budget,
                                       Long reservedQty, Long reservedVnd) {
        long[] used = jdbc.usedTotals(promotionId);
        runtime.writeBudgetProjection(promotionId, budget.getTotalRedemptionQuantity(), budget.getTotalBudgetVnd(),
                used[0], used[1], reservedQty, reservedVnd);
    }

    public long nowMs() {
        return clock.millis();
    }
}
