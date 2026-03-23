package com.ecommerce.promotion.application.service;

import com.ecommerce.promotion.application.dto.OrderCancelledEvent;
import com.ecommerce.promotion.application.dto.OrderPaidEvent;
import com.ecommerce.promotion.application.dto.OrderRefundedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service xử lý Inbound Order Events từ Kafka.
 * Theo thiết kế promotion_events.md - Section 3: Inbound Events.
 *
 * Consumer group: promo-order-consumer
 * Topic: t.order.events
 *
 * 3 loại event:
 * - ORDER_PAID    → Commit Redeem (Luồng 4.B)
 * - ORDER_CANCELLED → Rollback Cancel (Luồng 4.C)
 * - ORDER_REFUNDED  → Refund Budget (Luồng 5)
 *
 * Idempotency kép:
 * - Vòng 1 (Redis): SETNX → handled ở Infrastructure Kafka Adapter
 * - Vòng 2 (Database): promotion_event_idempotency → handled ở BudgetService
 */
@Service
public class OrderEventProcessingService {

    private static final Logger log = LoggerFactory.getLogger(OrderEventProcessingService.class);

    private final BudgetService budgetService;

    public OrderEventProcessingService(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    /**
     * Xử lý event ORDER_PAID → Commit Redeem cho từng promotion trong đơn hàng.
     * Theo thiết kế promotion_events.md - Section 3.1: ORDER_PAID
     * Theo thiết kế promotion_workflow.md - Luồng 4.B (CONFIRM)
     *
     * Flow:
     * 1. Lặp qua promotionsApplied[]
     * 2. Gọi BudgetService.commitRedeem() cho mỗi promotion
     *    - Redis Lua Script 3 (Commit Redeem)
     *    - Xóa Reservation Key
     *    - DB: idempotency → budget increment → wallet USED → usage REDEEMED
     *    - Outbox: COUPON_REDEEMED
     */
    public void processOrderPaid(OrderPaidEvent event) {
        log.info("Processing ORDER_PAID: orderId={}, userId={}, promotions={}",
                event.getOrderId(), event.getUserId(),
                event.getPromotionsApplied() != null ? event.getPromotionsApplied().size() : 0);

        if (event.getPromotionsApplied() == null || event.getPromotionsApplied().isEmpty()) {
            log.info("No promotions applied, skipping: orderId={}", event.getOrderId());
            return;
        }

        for (OrderPaidEvent.PromotionAppliedDto promo : event.getPromotionsApplied()) {
            String eventId = buildEventId(event.getOrderId(), promo.getPromotionId(), "REDEEM");
            try {
                budgetService.commitRedeem(
                        eventId,
                        event.getOrderId(),
                        event.getUserId(),
                        promo.getPromotionId(),
                        promo.getDiscountAmount());
            } catch (Exception e) {
                // Log lỗi nhưng tiếp tục xử lý các promotion còn lại
                log.error("Failed to commit redeem: orderId={}, promoId={}, error={}",
                        event.getOrderId(), promo.getPromotionId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Xử lý event ORDER_CANCELLED → Rollback Cancel cho từng promotion.
     * Theo thiết kế promotion_events.md - Section 3.2: ORDER_CANCELLED
     * Theo thiết kế promotion_workflow.md - Luồng 4.C (CANCEL)
     *
     * Flow:
     * 1. Lặp qua promotionsApplied[]
     * 2. Gọi BudgetService.cancelReservation() cho mỗi promotion
     *    - Redis Lua Script 4 (Rollback Cancel)
     *    - Xóa Reservation Key
     *    - DB: idempotency → wallet SAVED → usage CANCELLED
     *    - Outbox: COUPON_CANCELLED
     *
     * PHÂN BIỆT: Cancel nhả RESERVED, Refund nhả REDEEMED. MUST NOT mix.
     */
    public void processOrderCancelled(OrderCancelledEvent event) {
        log.info("Processing ORDER_CANCELLED: orderId={}, userId={}, reason={}, promotions={}",
                event.getOrderId(), event.getUserId(), event.getReason(),
                event.getPromotionsApplied() != null ? event.getPromotionsApplied().size() : 0);

        if (event.getPromotionsApplied() == null || event.getPromotionsApplied().isEmpty()) {
            log.info("No promotions applied, skipping cancel: orderId={}", event.getOrderId());
            return;
        }

        for (OrderCancelledEvent.PromotionAppliedDto promo : event.getPromotionsApplied()) {
            String eventId = buildEventId(event.getOrderId(), promo.getPromotionId(), "CANCEL");
            try {
                budgetService.cancelReservation(
                        eventId,
                        event.getOrderId(),
                        event.getUserId(),
                        promo.getPromotionId(),
                        promo.getDiscountAmount(),
                        event.getReason() != null ? event.getReason() : "ORDER_CANCELLED");
            } catch (Exception e) {
                log.error("Failed to cancel reservation: orderId={}, promoId={}, error={}",
                        event.getOrderId(), promo.getPromotionId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Xử lý event ORDER_REFUNDED → Refund Budget cho từng promotion.
     * Theo thiết kế promotion_events.md - Section 3.3: ORDER_REFUNDED
     * Theo thiết kế promotion_workflow.md - Luồng 5 (Refund Flow)
     *
     * Flow:
     * 1. Lặp qua promotionsApplied[]
     * 2. Gọi BudgetService.refundBudget() cho mỗi promotion
     *    - Redis Lua Script 5 (Refund Budget)
     *    - DB: idempotency → budget decrement → wallet SAVED → usage REFUNDED
     *    - Outbox: COUPON_REFUNDED
     *
     * PHÂN BIỆT: Refund nhả REDEEMED, Cancel nhả RESERVED. MUST NOT mix.
     */
    public void processOrderRefunded(OrderRefundedEvent event) {
        log.info("Processing ORDER_REFUNDED: orderId={}, userId={}, promotions={}",
                event.getOrderId(), event.getUserId(),
                event.getPromotionsApplied() != null ? event.getPromotionsApplied().size() : 0);

        if (event.getPromotionsApplied() == null || event.getPromotionsApplied().isEmpty()) {
            log.info("No promotions applied, skipping refund: orderId={}", event.getOrderId());
            return;
        }

        for (OrderRefundedEvent.PromotionAppliedDto promo : event.getPromotionsApplied()) {
            String eventId = buildEventId(event.getOrderId(), promo.getPromotionId(), "REFUND");
            try {
                budgetService.refundBudget(
                        eventId,
                        event.getOrderId(),
                        event.getUserId(),
                        promo.getPromotionId(),
                        promo.getDiscountAmount());
            } catch (Exception e) {
                log.error("Failed to refund budget: orderId={}, promoId={}, error={}",
                        event.getOrderId(), promo.getPromotionId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Build deterministic event ID cho DB idempotency.
     * Format: orderId_promotionId_action
     * Đảm bảo mỗi (order, promotion, action) chỉ xử lý 1 lần.
     */
    private String buildEventId(Long orderId, Long promotionId, String action) {
        return orderId + "_" + promotionId + "_" + action;
    }
}
