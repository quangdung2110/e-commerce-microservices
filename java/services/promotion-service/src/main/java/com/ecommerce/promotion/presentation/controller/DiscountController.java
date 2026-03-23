package com.ecommerce.promotion.presentation.controller;

import com.ecommerce.promotion.application.dto.CalculateDiscountRequest;
import com.ecommerce.promotion.application.dto.CalculateDiscountResponse;
import com.ecommerce.promotion.application.service.DiscountCalculationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller cho API tính giá giảm giỏ hàng.
 * Theo thiết kế promotion_workflow.md - Luồng 3: Tính Giá Giỏ Hàng (In-memory Rule Engine).
 *
 * Flow:
 * 1. Order/Cart Service gọi POST /v1/discounts/calculate
 * 2. Đọc Caffeine Cache (L1) lấy JSON AST Rules → RuleNode
 * 3. Visitor Pattern quét cấp tốc dưới RAM (< 1ms)
 * 4. Xếp hạng Priority, gạt bỏ mã không thỏa mãn, tính toán Math.min
 *
 * Endpoint:
 * - POST /v1/discounts/calculate → Tính discount cho toàn bộ payload giỏ hàng
 */
@RestController
@RequestMapping("/v1/discounts")
public class DiscountController {

    private static final Logger log = LoggerFactory.getLogger(DiscountController.class);

    private final DiscountCalculationService discountCalculationService;

    public DiscountController(DiscountCalculationService discountCalculationService) {
        this.discountCalculationService = discountCalculationService;
    }

    /**
     * Tính toán discount cho giỏ hàng.
     * Theo thiết kế promotion_workflow.md - Luồng 3:
     *   "Order/Cart Service bắn API /calculate-discount gửi toàn bộ Payload"
     *
     * Request body phải chứa: userId, cartTotalAmount, items.
     * Có thể kèm promotionCodes (mã cụ thể) hoặc để null (hệ thống tự gợi ý).
     *
     * @param request CalculateDiscountRequest payload
     * @return CalculateDiscountResponse với danh sách promotions đã áp dụng và tổng tiền giảm
     */
    @PostMapping("/calculate")
    public ResponseEntity<CalculateDiscountResponse> calculateDiscount(
            @Valid @RequestBody CalculateDiscountRequest request) {
        log.debug("API: Calculate discount for user={}, cartTotal={}",
                request.getUserId(), request.getCartTotalAmount());

        CalculateDiscountResponse response = discountCalculationService.calculateDiscount(request);

        log.info("API: Discount calculated: user={}, totalDiscount={}, appliedCount={}",
                request.getUserId(),
                response.getTotalDiscount(),
                response.getAppliedPromotions() != null ? response.getAppliedPromotions().size() : 0);

        return ResponseEntity.ok(response);
    }
}
