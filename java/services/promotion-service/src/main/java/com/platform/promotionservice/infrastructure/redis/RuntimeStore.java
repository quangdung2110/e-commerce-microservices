package com.platform.promotionservice.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code promo:{promotion_id}:runtime}: HASH gộp quota/counter hot path và claim-gate
 * fields (ADR-015). Không TTL; rebuild từ PostgreSQL. Tổng vô hạn lưu {@code -1}.
 */
@Component
@RequiredArgsConstructor
public class RuntimeStore {

    public static final String F_TOTAL_QTY = "total_redemption_quantity";
    public static final String F_TOTAL_VND = "total_budget_vnd";
    public static final String F_USED_QTY = "used_quantity";
    public static final String F_USED_VND = "used_vnd";
    public static final String F_RESERVED_QTY = "reserved_quantity";
    public static final String F_RESERVED_VND = "reserved_vnd";
    public static final String F_CLAIM_ENABLED = "claim_enabled";
    public static final String F_CLAIM_REVISION = "claim_revision";
    public static final String F_OPENS_AT = "opens_at_ms";
    public static final String F_CLOSES_AT = "closes_at_ms";

    public static final long UNLIMITED = -1L;

    private final StringRedisTemplate redis;

    public record RuntimeState(long totalRedemptionQuantity, long totalBudgetVnd,
                               long usedQuantity, long usedVnd,
                               long reservedQuantity, long reservedVnd,
                               boolean claimEnabled, long claimRevision,
                               long opensAtMs, long closesAtMs) {

        public boolean quantityExhausted() {
            return totalRedemptionQuantity >= 0 && usedQuantity + reservedQuantity >= totalRedemptionQuantity;
        }

        public boolean budgetExhaustedFor(long amountVnd) {
            return totalBudgetVnd >= 0 && usedVnd + reservedVnd + amountVnd > totalBudgetVnd;
        }
    }

    public Optional<RuntimeState> read(long promotionId) {
        List<Object> values = redis.opsForHash().multiGet(PromotionRedisKeys.runtime(promotionId), List.of(
                F_TOTAL_QTY, F_TOTAL_VND, F_USED_QTY, F_USED_VND, F_RESERVED_QTY, F_RESERVED_VND,
                F_CLAIM_ENABLED, F_CLAIM_REVISION, F_OPENS_AT, F_CLOSES_AT));
        if (values == null || values.get(2) == null) {
            return Optional.empty();
        }
        return Optional.of(new RuntimeState(
                parse(values.get(0), UNLIMITED), parse(values.get(1), UNLIMITED),
                parse(values.get(2), 0), parse(values.get(3), 0),
                parse(values.get(4), 0), parse(values.get(5), 0),
                "1".equals(values.get(6)), parse(values.get(7), 0),
                parse(values.get(8), 0), parse(values.get(9), 0)));
    }

    /** Ghi quota/used projection từ PostgreSQL; reserved chỉ ghi khi caller cung cấp. */
    public void writeBudgetProjection(long promotionId, Long totalRedemptionQuantity, Long totalBudgetVnd,
                                      long usedQuantity, long usedVnd, Long reservedQuantity, Long reservedVnd) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(F_TOTAL_QTY, Long.toString(totalRedemptionQuantity == null ? UNLIMITED : totalRedemptionQuantity));
        fields.put(F_TOTAL_VND, Long.toString(totalBudgetVnd == null ? UNLIMITED : totalBudgetVnd));
        fields.put(F_USED_QTY, Long.toString(usedQuantity));
        fields.put(F_USED_VND, Long.toString(usedVnd));
        if (reservedQuantity != null && reservedVnd != null) {
            fields.put(F_RESERVED_QTY, Long.toString(reservedQuantity));
            fields.put(F_RESERVED_VND, Long.toString(reservedVnd));
        } else {
            redis.opsForHash().putIfAbsent(PromotionRedisKeys.runtime(promotionId), F_RESERVED_QTY, "0");
            redis.opsForHash().putIfAbsent(PromotionRedisKeys.runtime(promotionId), F_RESERVED_VND, "0");
        }
        redis.opsForHash().putAll(PromotionRedisKeys.runtime(promotionId), fields);
    }

    /** Claim-gate fields: enabled/revision/[opens,closes) lấy từ PostgreSQL. */
    public void writeClaimGate(long promotionId, boolean enabled, long revision, long opensAtMs, long closesAtMs) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(F_CLAIM_ENABLED, enabled ? "1" : "0");
        fields.put(F_CLAIM_REVISION, Long.toString(revision));
        fields.put(F_OPENS_AT, Long.toString(opensAtMs));
        fields.put(F_CLOSES_AT, Long.toString(closesAtMs));
        redis.opsForHash().putAll(PromotionRedisKeys.runtime(promotionId), fields);
    }

    /** Đóng gate bảo thủ trước DB transaction pause/cancel (WF-PROMO-001). */
    public void closeClaimGate(long promotionId) {
        redis.opsForHash().put(PromotionRedisKeys.runtime(promotionId), F_CLAIM_ENABLED, "0");
    }

    private static long parse(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
