package com.platform.promotionservice.worker;

import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.domain.entity.PromotionCommandReceiptEntity;
import com.platform.promotionservice.domain.enums.CommandOperation;
import com.platform.promotionservice.infrastructure.persistence.CommandReceiptRepository;
import com.platform.promotionservice.infrastructure.persistence.PromotionJdbc;
import com.platform.promotionservice.infrastructure.persistence.PromotionRepository;
import com.platform.promotionservice.infrastructure.redis.ReservationStore;
import com.platform.promotionservice.service.CampaignScheduleService;
import com.platform.promotionservice.service.ClaimService;
import com.platform.promotionservice.service.FinalCommandService;
import com.platform.promotionservice.service.RuntimeRebuildService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Scheduled workers (docs/promotion/operations.md §11): campaign scheduler + DB
 * fallback, pending-claim sweeper, reservation sweeper, receipt-driven finalizer,
 * budget reconciliation, retention trimmers và startup rebuild. Mọi worker bounded
 * batch, retry-safe; không worker nào ghi PostgreSQL dựa trên Redis counter.
 */
@Component
@ConditionalOnProperty(prefix = "promotion.workers", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class PromotionWorkers {

    private static final int PROMOTION_PAGE = 200;
    private static final int PER_PROMOTION_BATCH = 200;

    private final CampaignScheduleService schedule;
    private final ClaimService claims;
    private final FinalCommandService finalCommands;
    private final RuntimeRebuildService runtimeRebuild;
    private final ReservationStore reservations;
    private final CommandReceiptRepository receipts;
    private final PromotionRepository promotions;
    private final PromotionJdbc jdbc;
    private final PromotionProperties properties;
    private final Clock clock;

    private volatile boolean ready;

    public boolean isReady() {
        return ready;
    }

    /** Startup probe: rebuild campaign ZSET và runtime claim fields/pool trước khi ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        try {
            int members = schedule.rebuildSchedule();
            int rebuilt = runtimeRebuild.rebuildAll();
            log.info("Promotion runtime bootstrap: schedule_members_added={} promotions_rebuilt={}", members, rebuilt);
            ready = true;
        } catch (RuntimeException e) {
            log.error("Promotion runtime bootstrap failed; fallback scan will retry", e);
        }
    }

    @Scheduled(fixedDelayString = "${promotion.schedule.loop-interval}")
    public void campaignScheduleLoop() {
        guarded("campaign-schedule", schedule::processDue);
    }

    @Scheduled(fixedDelayString = "${promotion.schedule.fallback-interval}")
    public void campaignFallback() {
        guarded("campaign-fallback", () -> {
            int added = schedule.rebuildSchedule();
            if (!ready) {
                runtimeRebuild.rebuildAll();
                ready = true;
            }
            return added;
        });
    }

    @Scheduled(fixedDelayString = "${promotion.claim.sweeper-interval}")
    public void pendingClaimSweeper() {
        guarded("pending-claim-sweeper", () -> {
            Instant threshold = clock.instant();
            int persisted = 0;
            for (Long promotionId : runtimePromotionIds()) {
                persisted += claims.sweepPending(promotionId, threshold, PER_PROMOTION_BATCH);
            }
            return persisted;
        });
    }

    /** Reservation quá hạn: RESERVED → release; CONFIRMING → receipt thì finalize, hết grace thì release. */
    @Scheduled(fixedDelayString = "${promotion.reservation.sweeper-interval}")
    public void reservationSweeper() {
        guarded("reservation-sweeper", () -> {
            long nowMs = clock.millis();
            int handled = 0;
            for (Long promotionId : runtimePromotionIds()) {
                for (Long orderId : reservations.dueOrders(promotionId, nowMs, PER_PROMOTION_BATCH)) {
                    handled += sweepReservation(promotionId, orderId) ? 1 : 0;
                }
            }
            return handled;
        });
    }

    private boolean sweepReservation(long promotionId, long orderId) {
        Optional<ReservationStore.ReservationPayload> payload = reservations.read(promotionId, orderId);
        if (payload.isEmpty()) {
            // Payload mất nhưng index còn: không đoán amount, reconcile từ DB + valid payloads.
            reservations.removeIndex(promotionId, orderId);
            runtimeRebuild.reconcile(promotionId);
            return true;
        }
        if (ReservationStore.STATE_CONFIRMING.equals(payload.get().state())) {
            Optional<PromotionCommandReceiptEntity> receipt = receipts.findByOperationTypeAndPromotionIdAndOrderId(
                    CommandOperation.CONFIRM, promotionId, orderId);
            if (receipt.isPresent()) {
                finalCommands.kickFinalizer(receipt.get());
                return true;
            }
            // Hết grace mà không có durable receipt: DB fail sau Prepare → late-payment policy của Order.
            ReservationStore.CancelCode code = reservations.cancel(promotionId, orderId, true);
            log.info("Released expired CONFIRMING reservation promotion={} order={} result={}", promotionId, orderId, code);
            if (code == ReservationStore.CancelCode.DRIFT) {
                runtimeRebuild.reconcile(promotionId);
            }
            return true;
        }
        ReservationStore.CancelCode code = reservations.cancel(promotionId, orderId, false);
        if (code == ReservationStore.CancelCode.DRIFT) {
            runtimeRebuild.reconcile(promotionId);
        }
        return true;
    }

    /** Receipt còn redis_finalized_at NULL quá ngưỡng: chạy lại finalize/repair, set marker sau hội tụ. */
    @Scheduled(fixedDelayString = "${promotion.finalizer.scan-interval}")
    public void receiptFinalizer() {
        guarded("receipt-finalizer", () -> {
            Instant before = clock.instant().minus(properties.getFinalizer().getStaleAfter());
            List<PromotionCommandReceiptEntity> stale = receipts.findUnfinalizedBefore(before,
                    PageRequest.of(0, properties.getFinalizer().getBatchSize()));
            stale.forEach(finalCommands::kickFinalizer);
            return stale.size();
        });
    }

    @Scheduled(fixedDelayString = "${promotion.reconciliation.interval}")
    public void budgetReconciliation() {
        guarded("budget-reconciliation", () -> {
            Instant since = clock.instant().minus(properties.getReconciliation().getRecentWindow());
            int repaired = 0;
            for (Long promotionId : jdbc.promotionsWithRecentActivity(since)) {
                if (runtimeRebuild.reconcile(promotionId)) {
                    repaired++;
                }
            }
            return repaired;
        });
    }

    @Scheduled(fixedDelayString = "${promotion.outbox.trim-interval}")
    public void outboxTrimmer() {
        guarded("outbox-trimmer", () -> jdbc.trimOutbox(clock.instant().minus(properties.getOutbox().getRetention())));
    }

    @Scheduled(fixedDelayString = "${promotion.receipt.trim-interval}")
    public void receiptTrimmer() {
        guarded("receipt-trimmer", () -> jdbc.trimReceipts(clock.instant()));
    }

    private List<Long> runtimePromotionIds() {
        java.util.ArrayList<Long> all = new java.util.ArrayList<>();
        long after = 0;
        while (true) {
            List<Long> page = promotions.runtimeIdsAfter(after, PageRequest.of(0, PROMOTION_PAGE));
            if (page.isEmpty()) {
                return all;
            }
            all.addAll(page);
            after = page.get(page.size() - 1);
        }
    }

    private void guarded(String name, java.util.function.IntSupplier work) {
        try {
            int count = work.getAsInt();
            if (count > 0) {
                log.debug("Worker {} handled {} item(s)", name, count);
            }
        } catch (RuntimeException e) {
            log.warn("Worker {} failed: {}", name, e.getMessage());
        }
    }
}
