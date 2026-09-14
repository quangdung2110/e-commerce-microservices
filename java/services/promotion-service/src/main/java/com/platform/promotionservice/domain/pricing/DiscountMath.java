package com.platform.promotionservice.domain.pricing;

import com.platform.promotionservice.domain.enums.DiscountType;

/**
 * Tính discount toàn đơn bằng integer VND (PROMO-BR-003): percentage theo basis
 * points có cap, fixed có clamp theo subtotal; {@code final = max(0, subtotal - discount)}.
 * Không phân bổ xuống SKU/order line (PROMO-BR-002).
 */
public final class DiscountMath {

    public static final int BASIS_POINTS_SCALE = 10_000;

    private DiscountMath() {
    }

    public static long discount(DiscountType type, long value, Long maxDiscountVnd, long subtotalVnd) {
        if (subtotalVnd <= 0) {
            return 0;
        }
        long raw = switch (type) {
            case PERCENTAGE -> {
                long computed = Math.floorDiv(Math.multiplyExact(subtotalVnd, value), BASIS_POINTS_SCALE);
                yield maxDiscountVnd == null ? computed : Math.min(computed, maxDiscountVnd);
            }
            case FIXED_VND -> value;
        };
        return Math.max(0, Math.min(raw, subtotalVnd));
    }

    public static long finalAmount(long subtotalVnd, long discountVnd) {
        return Math.max(0, subtotalVnd - discountVnd);
    }
}
