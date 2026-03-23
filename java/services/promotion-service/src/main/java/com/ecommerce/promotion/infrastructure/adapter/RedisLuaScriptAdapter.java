package com.ecommerce.promotion.infrastructure.adapter;

import com.ecommerce.promotion.application.port.RedisLuaScriptPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Infrastructure Adapter — Redis Lua Script execution.
 * Implements RedisLuaScriptPort theo thiết kế redis_caching_design.md - Section 3.
 *
 * Tất cả Redis Key đều dùng Hash Tag {promotion_id} để đảm bảo
 * Lua Script multi-key nằm trên cùng 1 Redis Cluster shard.
 * Theo thiết kế redis_caching_design.md - Section 2 (Hash Tags).
 */
@Component
public class RedisLuaScriptAdapter implements RedisLuaScriptPort {

    private static final Logger log = LoggerFactory.getLogger(RedisLuaScriptAdapter.class);

    private final StringRedisTemplate redisTemplate;

    // Pre-loaded Lua scripts (immutable, thread-safe)
    private final DefaultRedisScript<Long> spopClaimScript;
    private final DefaultRedisScript<Long> budgetReservationScript;
    private final DefaultRedisScript<Long> commitRedeemScript;
    private final DefaultRedisScript<Long> rollbackCancelScript;
    private final DefaultRedisScript<Long> refundBudgetScript;

    public RedisLuaScriptAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;

        // Load Lua scripts from classpath: resources/scripts/
        this.spopClaimScript = loadScript("scripts/spop_claiming.lua");
        this.budgetReservationScript = loadScript("scripts/budget_reservation.lua");
        this.commitRedeemScript = loadScript("scripts/commit_redeem.lua");
        this.rollbackCancelScript = loadScript("scripts/rollback_cancel.lua");
        this.refundBudgetScript = loadScript("scripts/refund_budget.lua");
    }

    private DefaultRedisScript<Long> loadScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    // =========================================================================
    // Script 1: SPOP Claiming (Flash Sale)
    // KEYS[1] = promo:{promotion_id}:claimed:user:{user_id}
    // KEYS[2] = promo:{promotion_id}:claim_pool
    // KEYS[3] = promo:{promotion_id}:pending_claims
    // ARGV[1] = "user_id:timestamp"
    // =========================================================================

    @Override
    public long spopClaim(Long promotionId, Long userId) {
        List<String> keys = List.of(
                "promo:{" + promotionId + "}:claimed:user:" + userId,
                "promo:{" + promotionId + "}:claim_pool",
                "promo:{" + promotionId + "}:pending_claims"
        );
        String argv = userId + ":" + Instant.now().getEpochSecond();

        Long result = redisTemplate.execute(spopClaimScript, keys, argv);
        long value = result != null ? result : -2;

        if (value > 0) {
            log.debug("SPOP claim success: promo={}, user={}, claimId={}", promotionId, userId, value);
        } else {
            log.debug("SPOP claim failed: promo={}, user={}, result={}", promotionId, userId, value);
        }
        return value;
    }

    @Override
    public void removePendingClaim(Long promotionId, Long claimId) {
        String key = "promo:{" + promotionId + "}:pending_claims";
        redisTemplate.opsForHash().delete(key, claimId.toString());
        log.debug("Removed pending claim: promo={}, claimId={}", promotionId, claimId);
    }

    // =========================================================================
    // Script 2: Budget Reservation (TRY Phase)
    // KEYS[1] = "promo:{promotion_id}:budget"
    // ARGV[1] = quantity, ARGV[2] = discount_amount
    // =========================================================================

    @Override
    public boolean reserveBudget(Long promotionId, int quantity, BigDecimal discountAmount) {
        List<String> keys = List.of("promo:{" + promotionId + "}:budget");

        Long result = redisTemplate.execute(
                budgetReservationScript, keys,
                String.valueOf(quantity), discountAmount.toPlainString());

        boolean success = result != null && result == 1;
        log.debug("Reserve budget: promo={}, qty={}, amt={}, result={}",
                promotionId, quantity, discountAmount, success);
        return success;
    }

    // =========================================================================
    // Script 3: Commit Redeem (CONFIRM Phase)
    // KEYS[1] = "promo:{promotion_id}:budget"
    // KEYS[2] = "promo:{promotion_id}:idemp:order:{order_id}:redeem"
    // ARGV[1] = quantity, ARGV[2] = discount_amount
    // =========================================================================

    @Override
    public boolean commitRedeem(Long promotionId, Long orderId, int quantity, BigDecimal discountAmount) {
        List<String> keys = List.of(
                "promo:{" + promotionId + "}:budget",
                "promo:{" + promotionId + "}:idemp:order:" + orderId + ":redeem"
        );

        Long result = redisTemplate.execute(
                commitRedeemScript, keys,
                String.valueOf(quantity), discountAmount.toPlainString());

        boolean success = result != null && result == 1;
        log.debug("Commit redeem: promo={}, order={}, result={}", promotionId, orderId, success);
        return success;
    }

    // =========================================================================
    // Script 4: Rollback Cancel (CANCEL Phase)
    // KEYS[1] = "promo:{promotion_id}:budget"
    // KEYS[2] = "promo:{promotion_id}:idemp:order:{order_id}:cancel"
    // ARGV[1] = quantity, ARGV[2] = discount_amount
    // =========================================================================

    @Override
    public boolean rollbackCancel(Long promotionId, Long orderId, int quantity, BigDecimal discountAmount) {
        List<String> keys = List.of(
                "promo:{" + promotionId + "}:budget",
                "promo:{" + promotionId + "}:idemp:order:" + orderId + ":cancel"
        );

        Long result = redisTemplate.execute(
                rollbackCancelScript, keys,
                String.valueOf(quantity), discountAmount.toPlainString());

        boolean success = result != null && result == 1;
        log.debug("Rollback cancel: promo={}, order={}, result={}", promotionId, orderId, success);
        return success;
    }

    // =========================================================================
    // Script 5: Refund Budget
    // KEYS[1] = "promo:{promotion_id}:budget"
    // KEYS[2] = "promo:{promotion_id}:idemp:order:{order_id}:refund"
    // ARGV[1] = quantity, ARGV[2] = discount_amount
    // =========================================================================

    @Override
    public boolean refundBudget(Long promotionId, Long orderId, int quantity, BigDecimal discountAmount) {
        List<String> keys = List.of(
                "promo:{" + promotionId + "}:budget",
                "promo:{" + promotionId + "}:idemp:order:" + orderId + ":refund"
        );

        Long result = redisTemplate.execute(
                refundBudgetScript, keys,
                String.valueOf(quantity), discountAmount.toPlainString());

        boolean success = result != null && result == 1;
        log.debug("Refund budget: promo={}, order={}, result={}", promotionId, orderId, success);
        return success;
    }

    // =========================================================================
    // Reservation TTL Tracking
    // Key: promo:{promotion_id}:reservation:{order_id}
    // Value: "quantity:discountAmount"
    // TTL: 900s (15 phút) — configurable via promotion.reservation.ttl-seconds
    // =========================================================================

    @Override
    public void setReservationTtl(Long promotionId, Long orderId,
                                  int quantity, BigDecimal discountAmount, long ttlSeconds) {
        String key = "promo:{" + promotionId + "}:reservation:" + orderId;
        String value = quantity + ":" + discountAmount.toPlainString();
        redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
        log.debug("Set reservation TTL: key={}, ttl={}s", key, ttlSeconds);
    }

    @Override
    public void deleteReservationKey(Long promotionId, Long orderId) {
        String key = "promo:{" + promotionId + "}:reservation:" + orderId;
        redisTemplate.delete(key);
        log.debug("Deleted reservation key: {}", key);
    }

    @Override
    public boolean existsReservationKey(Long promotionId, Long orderId) {
        String key = "promo:{" + promotionId + "}:reservation:" + orderId;
        Boolean exists = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(exists);
    }

    // =========================================================================
    // Budget Hash Warm-up
    // Key: promo:{promotion_id}:budget
    // Fields: limit_qty, reserved_qty, redeemed_qty, limit_budget, reserved_budget, redeemed_budget
    // =========================================================================

    @Override
    public void warmUpBudget(Long promotionId, int limitQty, BigDecimal limitBudget) {
        String key = "promo:{" + promotionId + "}:budget";

        // HSETNX logic: chỉ nạp nếu key chưa tồn tại (tránh ghi đè counter đang hoạt động)
        Boolean keyExists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(keyExists)) {
            log.debug("Budget key already exists, skipping warm-up: promo={}", promotionId);
            return;
        }

        Map<String, String> fields = new HashMap<>();
        fields.put("limit_qty", String.valueOf(limitQty));
        fields.put("reserved_qty", "0");
        fields.put("redeemed_qty", "0");
        fields.put("limit_budget", limitBudget.toPlainString());
        fields.put("reserved_budget", "0");
        fields.put("redeemed_budget", "0");

        redisTemplate.opsForHash().putAll(key, fields);
        log.info("Warmed up budget: promo={}, limitQty={}, limitBudget={}", promotionId, limitQty, limitBudget);
    }

    // =========================================================================
    // Claim Pool Pre-warming
    // Key: promo:{promotion_id}:claim_pool (Set)
    // =========================================================================

    @Override
    public void warmUpClaimPool(Long promotionId, List<Long> claimIds) {
        if (claimIds == null || claimIds.isEmpty()) return;

        String key = "promo:{" + promotionId + "}:claim_pool";
        String[] values = claimIds.stream()
                .map(String::valueOf)
                .toArray(String[]::new);

        redisTemplate.opsForSet().add(key, values);
        log.info("Warmed up claim pool: promo={}, count={}", promotionId, claimIds.size());
    }

    // =========================================================================
    // Pending Claims
    // Key: promo:{promotion_id}:pending_claims (Hash)
    // Field: claimId → "userId:timestamp"
    // =========================================================================

    @Override
    public Map<Long, String> getPendingClaims(Long promotionId) {
        String key = "promo:{" + promotionId + "}:pending_claims";
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);

        Map<Long, String> result = new HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            try {
                Long claimId = Long.parseLong(entry.getKey().toString());
                result.put(claimId, entry.getValue().toString());
            } catch (NumberFormatException e) {
                log.warn("Invalid pending claim key: {}", entry.getKey());
            }
        }
        return result;
    }
}
