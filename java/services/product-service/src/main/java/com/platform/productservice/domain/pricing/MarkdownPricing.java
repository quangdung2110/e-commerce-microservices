package com.platform.productservice.domain.pricing;

import com.platform.productservice.domain.entity.MarkdownProjectionEntity;
import com.platform.productservice.domain.enums.MarkdownState;

import java.math.BigInteger;
import java.time.Instant;

/**
 * Công thức giá công bố (docs/product/domain.md — Giá markdown):
 *
 * <pre>
 * PERCENTAGE: discount = floor(base_price_vnd * discount_bps / 10_000)
 *             discount = min(discount, max_discount_vnd) nếu có cap
 * FIXED_VND:  discount = fixed_discount_vnd
 * selling_price_vnd = max(0, base_price_vnd - discount)
 * </pre>
 *
 * <p>Product là nơi duy nhất tính selling price (PRODUCT-BR-005).
 */
public final class MarkdownPricing {

    private static final BigInteger BPS_DENOMINATOR = BigInteger.valueOf(10_000L);

    private MarkdownPricing() {
    }

    /**
     * Giá công bố của một SKU theo projection markdown hiện có.
     *
     * @param projection có thể null khi Product chưa từng nhận snapshot nào
     */
    public static long sellingPrice(long basePriceVnd, MarkdownProjectionEntity projection) {
        if (projection == null || projection.getState() != MarkdownState.ACTIVE) {
            return basePriceVnd;
        }
        long discount = discountOf(basePriceVnd, projection);
        return Math.max(0L, basePriceVnd - discount);
    }

    private static long discountOf(long basePriceVnd, MarkdownProjectionEntity projection) {
        return switch (projection.getDiscountType()) {
            case PERCENTAGE -> {
                int bps = projection.getDiscountBps() == null ? 0 : projection.getDiscountBps();
                long raw = BigInteger.valueOf(basePriceVnd)
                        .multiply(BigInteger.valueOf(bps))
                        .divide(BPS_DENOMINATOR)
                        .min(BigInteger.valueOf(Long.MAX_VALUE))
                        .longValue();
                Long cap = projection.getMaxDiscountVnd();
                yield cap == null ? raw : Math.min(raw, cap);
            }
            case FIXED_VND -> projection.getFixedDiscountVnd() == null ? 0L : projection.getFixedDiscountVnd();
        };
    }

    /**
     * Trạng thái được lưu cho một snapshot.
     *
     * <p>Snapshot {@code ACTIVE} đến muộn sau {@code ends_at} vẫn advance
     * {@code source_revision} nhưng được lưu {@code INACTIVE}, nên không làm giá
     * cũ có hiệu lực lại (docs/product/data.md §3.3). Product không có safety
     * schedule ở phía {@code starts_at}: Promotion sở hữu lịch.
     */
    public static MarkdownState effectiveState(MarkdownState declaredState, Instant endsAt, Instant now) {
        if (declaredState != MarkdownState.ACTIVE) {
            return MarkdownState.INACTIVE;
        }
        return now.isBefore(endsAt) ? MarkdownState.ACTIVE : MarkdownState.INACTIVE;
    }
}
