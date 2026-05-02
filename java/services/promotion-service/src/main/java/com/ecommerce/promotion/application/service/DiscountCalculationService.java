package com.ecommerce.promotion.application.service;

import com.ecommerce.promotion.application.dto.CalculateDiscountRequest;
import com.ecommerce.promotion.application.dto.CalculateDiscountResponse;
import com.ecommerce.promotion.application.dto.CalculateDiscountResponse.AppliedPromotionDto;
import com.ecommerce.promotion.application.exception.ResourceNotFoundException;
import com.ecommerce.promotion.domain.entity.Promotion;
import com.ecommerce.promotion.domain.enums.PromotionStatus;
import com.ecommerce.promotion.domain.repository.PromotionRepository;
import com.ecommerce.promotion.domain.rule.EvaluationContext;
import com.ecommerce.promotion.domain.rule.RuleNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service tính toán giá giảm (Cart Discount Calculator).
 * Theo thiết kế promotion_workflow.md - Luồng 3: Tính Giá Giỏ Hàng (In-memory
 * Rule Engine).
 *
 * Flow:
 * 1. Đọc Caffeine Cache (L1) lấy JSON AST Rules đã Parse sẵn thành RuleNode
 * Object.
 * 2. Dùng Visitor Pattern quét Node Object dưới RAM.
 * 3. Xếp hạng Priority, gạt bỏ mã không thỏa mãn, tính toán Math.min.
 */
@Service
public class DiscountCalculationService {

    private static final Logger log = LoggerFactory.getLogger(DiscountCalculationService.class);

    private final PromotionRepository promotionRepository;
    private final ObjectMapper objectMapper;

    public DiscountCalculationService(PromotionRepository promotionRepository, ObjectMapper objectMapper) {
        this.promotionRepository = promotionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Tính toán discount cho giỏ hàng.
     * Theo thiết kế promotion_workflow.md - Luồng 3:
     * Order/Cart Service bắn API /calculate-discount gửi toàn bộ Payload.
     * Promotion Backend đọc Caffeine Cache (L1) lấy đã Parse sẵn RuleNode.
     * Evaluation dùng Visitor Pattern quét tốc độ < 1ms.
     */
    public CalculateDiscountResponse calculateDiscount(CalculateDiscountRequest request) {
        Instant now = Instant.now();
        List<Promotion> candidatePromotions;

        if (request.getPromotionCodes() != null && !request.getPromotionCodes().isEmpty()) {
            // User chỉ định mã cụ thể
            candidatePromotions = request.getPromotionCodes().stream()
                    .map(code -> promotionRepository.findByCode(code)
                            .orElseThrow(() -> new ResourceNotFoundException("Promotion", code)))
                    .collect(Collectors.toList());
        } else {
            // Tự tìm eligible promotions dựa trên cartTotal
            // Theo thiết kế promotion_db_design.md - Section 1.2: "Gợi ý mã khuyến mãi"
            candidatePromotions = promotionRepository.findEligiblePromotions(now, request.getCartTotalAmount());
        }

        // Build EvaluationContext từ request payload
        // Theo thiết kế rule_engine_design.md: EvaluationContext là flat map
        Map<String, Object> contextMap = buildContextMap(request);
        EvaluationContext context = new EvaluationContext(contextMap);

        List<AppliedPromotionDto> appliedList = new ArrayList<>();
        BigDecimal totalDiscount = BigDecimal.ZERO;

        // Sort by priority DESC — cao áp dụng trước
        // Theo thiết kế promotion_db_design.md - Section 1.2: priority
        candidatePromotions.sort(Comparator.comparingInt(p -> -p.getPriority()));

        for (Promotion promo : candidatePromotions) {
            // Validate status + thời hạn
            if (promo.getStatus() != PromotionStatus.ACTIVE)
                continue;
            if (promo.getStartTime().isAfter(now) || promo.getEndTime().isBefore(now))
                continue;

            // Check min_purchase_amount
            if (promo.getMinPurchaseAmount() != null
                    && request.getCartTotalAmount().compareTo(promo.getMinPurchaseAmount()) < 0) {
                continue;
            }

            // Evaluate Rule Engine AST
            // Theo thiết kế promotion_workflow.md - Luồng 3 Bước 3
            // conditionsRule is stored as JSON String, parse to RuleNode via Jackson
            String conditionsJson = promo.getConditionsRule();
            if (conditionsJson != null && !conditionsJson.isBlank()) {
                try {
                    RuleNode ruleNode = objectMapper.readValue(conditionsJson, RuleNode.class);
                    if (!ruleNode.evaluate(context)) {
                        log.debug("Rule conditions not met for promotion {}", promo.getId());
                        // Catch split source of truth: DB min_purchase passed but AST failed
                        if (promo.getMinPurchaseAmount() != null
                                && request.getCartTotalAmount().compareTo(promo.getMinPurchaseAmount()) >= 0) {
                            log.warn(
                                    "Split source of truth detected for promotion {}: DB min_purchase_amount passed but AST condition failed",
                                    promo.getId());
                        }
                        continue;
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse rule for promotion {}: {}", promo.getId(), e.getMessage());
                    continue;
                }
            }

            // Tính discount amount
            BigDecimal discountAmount = calculateDiscountAmount(promo, request.getCartTotalAmount());
            if (discountAmount.compareTo(BigDecimal.ZERO) <= 0)
                continue;

            // Tính phân chia funding (Platform vs Seller)
            // Theo thiết kế promotion_db_design.md - Section 1.6: platform_funding,
            // seller_funding
            BigDecimal platformFunding;
            BigDecimal sellerFunding;
            if ("PLATFORM".equals(promo.getOwnerType().name())) {
                platformFunding = discountAmount;
                sellerFunding = BigDecimal.ZERO;
            } else {
                platformFunding = BigDecimal.ZERO;
                sellerFunding = discountAmount;
            }

            AppliedPromotionDto dto = new AppliedPromotionDto();
            dto.setPromotionId(promo.getId());
            dto.setCode(promo.getCode());
            dto.setDiscountType(promo.getDiscountType().name());
            dto.setDiscountValue(promo.getDiscountValue());
            dto.setDiscountAmount(discountAmount);
            dto.setPlatformFunding(platformFunding);
            dto.setSellerFunding(sellerFunding);

            appliedList.add(dto);
            totalDiscount = totalDiscount.add(discountAmount);
        }

        CalculateDiscountResponse response = new CalculateDiscountResponse();
        response.setOriginalTotal(request.getCartTotalAmount());
        response.setTotalDiscount(totalDiscount);
        response.setFinalTotal(request.getCartTotalAmount().subtract(totalDiscount).max(BigDecimal.ZERO));
        response.setAppliedPromotions(appliedList);

        return response;
    }

    /**
     * Tính discount amount cho 1 promotion dựa trên discount_type.
     * Theo thiết kế promotion_db_design.md - Section 1.2:
     * discount_type: PERCENTAGE, FIXED_AMOUNT, FREE_SHIPPING
     * max_discount_amount: Giảm tối đa (áp dụng cho PERCENTAGE)
     */
    private BigDecimal calculateDiscountAmount(Promotion promo, BigDecimal cartTotal) {
        BigDecimal discount;

        switch (promo.getDiscountType()) {
            case PERCENTAGE:
                // discount = cartTotal * (discount_value / 100)
                discount = cartTotal.multiply(promo.getDiscountValue())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                // Math.min(discount, max_discount_amount)
                if (promo.getMaxDiscountAmount() != null) {
                    discount = discount.min(promo.getMaxDiscountAmount());
                }
                break;
            case FIXED_AMOUNT:
                discount = promo.getDiscountValue();
                break;
            case FREE_SHIPPING:
                // Shipping discount — cần thông tin shipping fee, tạm return discount_value
                discount = promo.getDiscountValue() != null ? promo.getDiscountValue() : BigDecimal.ZERO;
                break;
            default:
                discount = BigDecimal.ZERO;
        }

        // Không cho discount vượt quá cartTotal
        return discount.min(cartTotal);
    }

    /**
     * Build EvaluationContext flat map từ request.
     * Theo thiết kế rule_engine_design.md: flat map fields.
     */
    private Map<String, Object> buildContextMap(CalculateDiscountRequest request) {
        Map<String, Object> map = new HashMap<>();
        map.put("cart.total_amount", request.getCartTotalAmount());
        map.put("user.id", request.getUserId());
        if (request.getPaymentMethod() != null) {
            map.put("payment.method", request.getPaymentMethod());
        }
        if (request.getUserTier() != null) {
            map.put("user.tier", request.getUserTier());
        }
        if (request.getItems() != null) {
            // item count
            map.put("cart.item_count", request.getItems().size());
            // Category IDs set
            map.put("cart.category_ids", request.getItems().stream()
                    .map(CalculateDiscountRequest.CartItemDto::getCategoryId)
                    .filter(Objects::nonNull).distinct().collect(Collectors.toList()));
            // Brand IDs set
            map.put("cart.brand_ids", request.getItems().stream()
                    .map(CalculateDiscountRequest.CartItemDto::getBrandId)
                    .filter(Objects::nonNull).distinct().collect(Collectors.toList()));
            // Seller IDs set
            map.put("cart.seller_ids", request.getItems().stream()
                    .map(CalculateDiscountRequest.CartItemDto::getSellerId)
                    .filter(Objects::nonNull).distinct().collect(Collectors.toList()));
        }
        return map;
    }
}
