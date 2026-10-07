package com.platform.promotionservice.infrastructure.redis;

import com.platform.promotionservice.config.PromotionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Snapshot Redis đơn tầng (ADR-002): {@code promo:{id}:snapshot} JSON TTL 3600s và
 * {@code promo:rules:version} làm version fence. Miss/lỗi Redis → caller đọc DB.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SnapshotCache {

    private final StringRedisTemplate redis;
    private final PromotionProperties properties;

    public long currentVersion() {
        try {
            String value = redis.opsForValue().get(PromotionRedisKeys.RULES_VERSION);
            return value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException e) {
            log.debug("Snapshot version read failed: {}", e.getMessage());
            return -1;
        }
    }

    public Optional<String> get(long promotionId) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(PromotionRedisKeys.snapshot(promotionId)));
        } catch (RuntimeException e) {
            log.debug("Snapshot read failed for promotion {}: {}", promotionId, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(long promotionId, String json) {
        try {
            redis.opsForValue().set(PromotionRedisKeys.snapshot(promotionId), json, properties.getSnapshot().getTtl());
        } catch (RuntimeException e) {
            log.debug("Snapshot write failed for promotion {}: {}", promotionId, e.getMessage());
        }
    }

    public void evict(long promotionId) {
        redis.delete(PromotionRedisKeys.snapshot(promotionId));
    }

    /** Tăng version fence sau khi admin mutation đã commit; stale snapshot bị bỏ. */
    public void bumpVersion() {
        redis.opsForValue().increment(PromotionRedisKeys.RULES_VERSION);
    }
}
