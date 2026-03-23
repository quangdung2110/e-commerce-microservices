package com.ecommerce.promotion.presentation.controller;

import com.ecommerce.promotion.application.dto.ReserveBudgetRequest;
import com.ecommerce.promotion.application.dto.ReserveBudgetResponse;
import com.ecommerce.promotion.application.service.BudgetService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller cho API Budget Reservation (Saga TRY Phase).
 * Theo thiết kế promotion_workflow.md - Luồng 4: Saga Checkout Flow.
 *
 * Flow:
 * 1. Order Service gọi POST /v1/budgets/reserve khi user "Xác nhận Đặt hàng"
 * 2. Redis Lua Script 2 (Budget Reservation) — check limit_qty + limit_budget
 * 3. Tạo Reservation TTL Key (15 phút) + ghi DB coupon_usages(RESERVED)
 * 4. Trả response "Khoá thành công" + TTL info
 *
 * Lưu ý: CONFIRM/CANCEL/REFUND được xử lý qua Kafka events (OrderEventKafkaConsumer),
 * KHÔNG có REST API riêng cho các bước này.
 *
 * Endpoints:
 * - POST /v1/budgets/reserve → Giữ chỗ ngân sách (TRY Phase)
 */
@RestController
@RequestMapping("/v1/budgets")
public class BudgetController {

    private static final Logger log = LoggerFactory.getLogger(BudgetController.class);

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    /**
     * Giữ chỗ ngân sách cho đơn hàng (TRY Phase).
     * Theo thiết kế promotion_workflow.md - Luồng 4.A:
     *   "Order Service gọi API /reserve khi khách bấm Xác nhận Đặt hàng"
     *   → Redis Lua Script 2 (Budget Reservation)
     *   → Reservation TTL Key (15 phút)
     *   → DB coupon_usages(status=RESERVED)
     *
     * @param request ReserveBudgetRequest payload
     * @return ReserveBudgetResponse với danh sách promotions đã reserve thành công/thất bại
     */
    @PostMapping("/reserve")
    public ResponseEntity<ReserveBudgetResponse> reserveBudget(
            @Valid @RequestBody ReserveBudgetRequest request) {
        log.debug("API: Reserve budget for order={}, user={}", request.getOrderId(), request.getUserId());

        ReserveBudgetResponse response = budgetService.reserveBudget(request);

        log.info("API: Budget reservation: order={}, success={}, items={}",
                request.getOrderId(),
                response.isSuccess(),
                response.getReservedPromotions() != null ? response.getReservedPromotions().size() : 0);

        return ResponseEntity.ok(response);
    }
}
