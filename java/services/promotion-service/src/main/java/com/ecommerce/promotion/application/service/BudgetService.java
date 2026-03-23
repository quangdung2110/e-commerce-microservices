package com.ecommerce.promotion.application.service;

import com.ecommerce.promotion.application.dto.ReserveBudgetRequest;
import com.ecommerce.promotion.application.dto.ReserveBudgetRequest.ReservationItemDto;
import com.ecommerce.promotion.application.dto.ReserveBudgetResponse;
import com.ecommerce.promotion.application.dto.ReserveBudgetResponse.ReservedItemDto;
import com.ecommerce.promotion.application.exception.BudgetExhaustedException;
import com.ecommerce.promotion.application.port.RedisLuaScriptPort;
import com.ecommerce.promotion.domain.entity.CouponUsage;
import com.ecommerce.promotion.domain.entity.PromotionEventIdempotency;
import com.ecommerce.promotion.domain.entity.UserCouponWallet;
import com.ecommerce.promotion.domain.enums.IdempotencyActionType;
import com.ecommerce.promotion.domain.enums.UsageStatus;
import com.ecommerce.promotion.domain.enums.WalletStatus;
import com.ecommerce.promotion.domain.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service quản lý Budget Reservation (Saga TRY/CONFIRM/CANCEL/REFUND).
 * Theo thiết kế promotion_workflow.md - Luồng 4: Saga Checkout Flow.
 *
 * Flow:
 * - TRY (Reserve):   Redis Lua Script 2 → Reservation TTL Key → DB coupon_usages(RESERVED)
 * - CONFIRM (Redeem): Redis Lua Script 3 → Delete TTL Key → DB budget/wallet/usages update
 * - CANCEL (Rollback): Redis Lua Script 4 → Delete TTL Key → DB wallet/usages update
 * - REFUND:           Redis Lua Script 5 → DB budget/wallet/usages update
 */
@Service
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    private final PromotionBudgetRepository budgetRepository;
    private final CouponUsageRepository usageRepository;
    private final UserCouponWalletRepository walletRepository;
    private final PromotionEventIdempotencyRepository idempotencyRepository;
    private final RedisLuaScriptPort redisLuaScriptPort;
    private final OutboxService outboxService;

    @Value("${promotion.reservation.ttl-seconds:900}")
    private long reservationTtlSeconds;

    public BudgetService(
            PromotionBudgetRepository budgetRepository,
            CouponUsageRepository usageRepository,
            UserCouponWalletRepository walletRepository,
            PromotionEventIdempotencyRepository idempotencyRepository,
            RedisLuaScriptPort redisLuaScriptPort,
            OutboxService outboxService) {
        this.budgetRepository = budgetRepository;
        this.usageRepository = usageRepository;
        this.walletRepository = walletRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.redisLuaScriptPort = redisLuaScriptPort;
        this.outboxService = outboxService;
    }

    // =========================================================================
    // TRY Phase: Reserve Budget
    // Theo thiết kế promotion_workflow.md - Luồng 4.A
    // =========================================================================

    /**
     * Giữ chỗ ngân sách cho mỗi promotion trong đơn hàng.
     * Theo thiết kế promotion_workflow.md - Bước A:
     * 1. Redis Lua Script 2 (Budget Reservation)
     * 2. Tạo Reservation TTL Key (15 phút)
     * 3. Ghi DB coupon_usages(status=RESERVED)
     */
    @Transactional
    public ReserveBudgetResponse reserveBudget(ReserveBudgetRequest request) {
        List<ReservedItemDto> reservedItems = new ArrayList<>();
        boolean allSuccess = true;

        for (ReservationItemDto item : request.getPromotions()) {
            // Bước 2: Redis Lua Script 2 — Budget Reservation
            boolean reserved = redisLuaScriptPort.reserveBudget(
                    item.getPromotionId(), 1, item.getDiscountAmount());

            ReservedItemDto reservedItem = new ReservedItemDto();
            reservedItem.setPromotionId(item.getPromotionId());
            reservedItem.setDiscountAmount(item.getDiscountAmount());

            if (reserved) {
                reservedItem.setReserved(true);

                // Bước 3: Tạo Reservation TTL Key
                // Theo thiết kế redis_caching_design.md - Section 2.4
                redisLuaScriptPort.setReservationTtl(
                        item.getPromotionId(), request.getOrderId(),
                        1, item.getDiscountAmount(), reservationTtlSeconds);

                // Ghi DB: INSERT coupon_usages(status=RESERVED)
                // Theo thiết kế promotion_db_design.md - Section 1.6
                CouponUsage usage = new CouponUsage();
                usage.setUserId(request.getUserId());
                usage.setPromotionId(item.getPromotionId());
                usage.setOrderId(request.getOrderId());
                usage.setDiscountAmount(item.getDiscountAmount());
                usage.setPlatformFunding(item.getPlatformFunding());
                usage.setSellerFunding(item.getSellerFunding());
                usage.setStatus(UsageStatus.RESERVED);
                usage.setReservedAt(Instant.now());
                usageRepository.save(usage);

                log.info("Reserved budget: promo={}, order={}, amount={}",
                        item.getPromotionId(), request.getOrderId(), item.getDiscountAmount());
            } else {
                reservedItem.setReserved(false);
                reservedItem.setFailReason("BUDGET_EXHAUSTED");
                allSuccess = false;
                log.warn("Budget exhausted for promo={}", item.getPromotionId());
            }

            reservedItems.add(reservedItem);
        }

        // Nếu bất kỳ promotion nào reserve thất bại, rollback tất cả đã reserve
        if (!allSuccess) {
            for (ReservedItemDto item : reservedItems) {
                if (item.isReserved()) {
                    redisLuaScriptPort.rollbackCancel(
                            item.getPromotionId(), request.getOrderId(),
                            1, item.getDiscountAmount());
                    redisLuaScriptPort.deleteReservationKey(item.getPromotionId(), request.getOrderId());
                }
            }
            throw new BudgetExhaustedException(
                    reservedItems.stream()
                            .filter(i -> !i.isReserved())
                            .findFirst().map(ReservedItemDto::getPromotionId).orElse(0L));
        }

        // Ghi outbox event: COUPON_RESERVED
        // Theo thiết kế promotion_events.md - Section 2.5
        for (ReservationItemDto item : request.getPromotions()) {
            outboxService.publishEvent(
                    "COUPON_USAGE",
                    request.getOrderId().toString(),
                    "COUPON_RESERVED",
                    Map.of(
                            "promotion_id", item.getPromotionId(),
                            "order_id", request.getOrderId(),
                            "user_id", request.getUserId(),
                            "discount_amount", item.getDiscountAmount(),
                            "platform_funding", item.getPlatformFunding(),
                            "seller_funding", item.getSellerFunding(),
                            "reserved_at", Instant.now().toEpochMilli()
                    ));
        }

        ReserveBudgetResponse response = new ReserveBudgetResponse();
        response.setOrderId(request.getOrderId());
        response.setSuccess(true);
        response.setReservationTtlSeconds(reservationTtlSeconds);
        response.setReservedPromotions(reservedItems);
        return response;
    }

    // =========================================================================
    // CONFIRM Phase: Commit Redeem
    // Theo thiết kế promotion_workflow.md - Luồng 4.B
    // =========================================================================

    /**
     * Xác nhận mua thành công — chuyển Reserved → Redeemed.
     * Theo thiết kế promotion_workflow.md - Bước B:
     * 1. Redis Lua Script 3 (Commit Redeem) với SETNX idempotency
     * 2. Xóa Reservation Key
     * 3. DB: Idempotency check → Update budget → Update wallet → Update usage
     *
     * @param eventId    Kafka event ID (dùng cho DB idempotency)
     * @param orderId    ID đơn hàng
     * @param userId     ID user
     * @param promotionId ID promotion
     * @param discountAmount Số tiền giảm
     */
    @Transactional
    public void commitRedeem(String eventId, Long orderId, Long userId,
                             Long promotionId, BigDecimal discountAmount) {
        // Vòng 2 (Database Level): Chống lặp
        // Theo thiết kế promotion_db_design.md - Section 1.7
        if (idempotencyRepository.existsByEventId(eventId)) {
            log.info("Duplicate redeem event ignored: eventId={}", eventId);
            return;
        }

        // Vòng 1 (Redis Level): Lua Script 3 — Commit Redeem
        redisLuaScriptPort.commitRedeem(promotionId, orderId, 1, discountAmount);

        // Xóa Reservation Key
        // Theo thiết kế promotion_workflow.md - Bước B.3
        redisLuaScriptPort.deleteReservationKey(promotionId, orderId);

        // Ghi DB Idempotency
        PromotionEventIdempotency idemp = new PromotionEventIdempotency();
        idemp.setEventId(eventId);
        idemp.setOrderId(orderId);
        idemp.setActionType(IdempotencyActionType.REDEEM_COUPON);
        idemp.setProcessedAt(Instant.now());
        idempotencyRepository.save(idemp);

        // Update promotion_budgets
        // Theo thiết kế promotion_db_design.md - Section 1.3: Worker chạy UPDATE đơn giản
        budgetRepository.incrementUsed(promotionId, 1, discountAmount);

        // Update user_coupon_wallets SET status = 'USED'
        // Theo thiết kế promotion_workflow.md - Bước B.4
        walletRepository.findByUserIdAndPromotionId(userId, promotionId)
                .ifPresent(wallet -> {
                    wallet.setStatus(WalletStatus.USED);
                    wallet.setUsedAt(Instant.now());
                    walletRepository.save(wallet);
                });

        // Update coupon_usages SET status = 'REDEEMED'
        usageRepository.findByOrderIdAndPromotionId(orderId, promotionId)
                .ifPresent(usage -> {
                    usage.setStatus(UsageStatus.REDEEMED);
                    usage.setRedeemedAt(Instant.now());
                    usageRepository.save(usage);
                });

        // Outbox event: COUPON_REDEEMED
        // Theo thiết kế promotion_events.md - Section 2.6
        outboxService.publishEvent(
                "COUPON_USAGE",
                orderId.toString(),
                "COUPON_REDEEMED",
                Map.of(
                        "promotion_id", promotionId,
                        "order_id", orderId,
                        "user_id", userId,
                        "discount_amount", discountAmount,
                        "redeemed_at", Instant.now().toEpochMilli()
                ));

        log.info("Committed redeem: promo={}, order={}", promotionId, orderId);
    }

    // =========================================================================
    // CANCEL Phase: Rollback
    // Theo thiết kế promotion_workflow.md - Luồng 4.C
    // =========================================================================

    /**
     * Nhả ngân sách khi đơn hàng bị hủy hoặc TTL hết hạn.
     * Theo thiết kế promotion_workflow.md - Bước C:
     * 1. Redis Lua Script 4 (Rollback Cancel) với SETNX idempotency
     * 2. Xóa Reservation Key
     * 3. DB: Idempotency → Wallet → Usage update
     *
     * @param reason "ORDER_CANCELLED" hoặc "RESERVATION_TTL_EXPIRED"
     */
    @Transactional
    public void cancelReservation(String eventId, Long orderId, Long userId,
                                  Long promotionId, BigDecimal discountAmount, String reason) {
        // Vòng 2 (Database Level): Chống lặp
        if (idempotencyRepository.existsByEventId(eventId)) {
            log.info("Duplicate cancel event ignored: eventId={}", eventId);
            return;
        }

        // Vòng 1 (Redis Level): Lua Script 4 — Rollback Cancel
        redisLuaScriptPort.rollbackCancel(promotionId, orderId, 1, discountAmount);

        // Xóa Reservation Key
        redisLuaScriptPort.deleteReservationKey(promotionId, orderId);

        // Ghi DB Idempotency
        PromotionEventIdempotency idemp = new PromotionEventIdempotency();
        idemp.setEventId(eventId);
        idemp.setOrderId(orderId);
        idemp.setActionType(IdempotencyActionType.CANCEL_COUPON);
        idemp.setProcessedAt(Instant.now());
        idempotencyRepository.save(idemp);

        // Đảo nghịch trạng thái Ví User về 'SAVED'
        // Theo thiết kế promotion_workflow.md - Bước C.4
        walletRepository.findByUserIdAndPromotionId(userId, promotionId)
                .ifPresent(wallet -> {
                    wallet.setStatus(WalletStatus.SAVED);
                    walletRepository.save(wallet);
                });

        // Cập nhật coupon_usages về CANCELLED
        usageRepository.findByOrderIdAndPromotionId(orderId, promotionId)
                .ifPresent(usage -> {
                    usage.setStatus(UsageStatus.CANCELLED);
                    usage.setCancelledAt(Instant.now());
                    usageRepository.save(usage);
                });

        // Outbox event: COUPON_CANCELLED
        // Theo thiết kế promotion_events.md - Section 2.7
        outboxService.publishEvent(
                "COUPON_USAGE",
                orderId.toString(),
                "COUPON_CANCELLED",
                Map.of(
                        "promotion_id", promotionId,
                        "order_id", orderId,
                        "user_id", userId,
                        "discount_amount", discountAmount,
                        "reason", reason,
                        "cancelled_at", Instant.now().toEpochMilli()
                ));

        log.info("Cancelled reservation: promo={}, order={}, reason={}", promotionId, orderId, reason);
    }

    // =========================================================================
    // REFUND Phase
    // Theo thiết kế promotion_workflow.md - Luồng 5: Refund Flow
    // =========================================================================

    /**
     * Hoàn ngân sách khi đổi trả hàng.
     * Theo thiết kế promotion_workflow.md - Luồng 5:
     * 1. Redis Lua Script 5 (Refund Budget) — trừ redeemed
     * 2. DB: Idempotency → Budget decrement → Wallet → Usage update
     *
     * PHÂN BIỆT: Cancel nhả reserved, Refund nhả redeemed.
     */
    @Transactional
    public void refundBudget(String eventId, Long orderId, Long userId,
                             Long promotionId, BigDecimal discountAmount) {
        // Vòng 2 (Database Level): Chống lặp
        if (idempotencyRepository.existsByEventId(eventId)) {
            log.info("Duplicate refund event ignored: eventId={}", eventId);
            return;
        }

        // Vòng 1 (Redis Level): Lua Script 5 — Refund Budget
        redisLuaScriptPort.refundBudget(promotionId, orderId, 1, discountAmount);

        // Ghi DB Idempotency
        PromotionEventIdempotency idemp = new PromotionEventIdempotency();
        idemp.setEventId(eventId);
        idemp.setOrderId(orderId);
        idemp.setActionType(IdempotencyActionType.REFUND_COUPON);
        idemp.setProcessedAt(Instant.now());
        idempotencyRepository.save(idemp);

        // UPDATE promotion_budgets SET used_quantity - 1, used_budget - amount
        // Theo thiết kế promotion_workflow.md - Luồng 5 Bước 3
        budgetRepository.decrementUsed(promotionId, 1, discountAmount);

        // Đảo nghịch trạng thái Ví User về 'SAVED' (user có thể tái sử dụng mã)
        walletRepository.findByUserIdAndPromotionId(userId, promotionId)
                .ifPresent(wallet -> {
                    wallet.setStatus(WalletStatus.SAVED);
                    walletRepository.save(wallet);
                });

        // Cập nhật coupon_usages về REFUNDED
        usageRepository.findByOrderIdAndPromotionId(orderId, promotionId)
                .ifPresent(usage -> {
                    usage.setStatus(UsageStatus.REFUNDED);
                    usage.setRefundedAt(Instant.now());
                    usageRepository.save(usage);
                });

        // Outbox event: COUPON_REFUNDED
        // Theo thiết kế promotion_events.md - Section 2.8
        outboxService.publishEvent(
                "COUPON_USAGE",
                orderId.toString(),
                "COUPON_REFUNDED",
                Map.of(
                        "promotion_id", promotionId,
                        "order_id", orderId,
                        "user_id", userId,
                        "discount_amount", discountAmount,
                        "refunded_at", Instant.now().toEpochMilli()
                ));

        log.info("Refunded budget: promo={}, order={}", promotionId, orderId);
    }

    // =========================================================================
    // Reservation TTL Sweep — Tự động nhả Budget bị khóa quá 15 phút
    // Theo thiết kế promotion_workflow.md - Bước D
    // Theo thiết kế promotion_workers.md - Section 2.3
    // =========================================================================

    /**
     * Sweep các reservation đã quá hạn.
     * Background Job chạy mỗi 2 phút.
     * Query coupon_usages(status=RESERVED, reserved_at < NOW - 15 phút).
     */
    public void sweepExpiredReservations() {
        Instant cutoff = Instant.now().minusSeconds(reservationTtlSeconds);
        List<CouponUsage> expired = usageRepository.findExpiredReservations(cutoff);

        for (CouponUsage usage : expired) {
            // Kiểm tra Redis Key: nếu đã mất (TTL expired) → trigger Cancel
            // Theo thiết kế redis_caching_design.md - Section 2.4 Background Job Sweep
            boolean keyExists = redisLuaScriptPort.existsReservationKey(
                    usage.getPromotionId(), usage.getOrderId());

            if (!keyExists) {
                // Key đã expire → nhả budget
                String eventId = "TTL_SWEEP_" + usage.getOrderId() + "_" + usage.getPromotionId();
                cancelReservation(eventId, usage.getOrderId(), usage.getUserId(),
                        usage.getPromotionId(), usage.getDiscountAmount(), "RESERVATION_TTL_EXPIRED");
                log.info("Swept expired reservation: order={}, promo={}",
                        usage.getOrderId(), usage.getPromotionId());
            }
        }
    }
}
