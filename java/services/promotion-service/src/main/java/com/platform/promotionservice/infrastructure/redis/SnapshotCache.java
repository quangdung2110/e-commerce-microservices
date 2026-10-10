package com.platform.promotionservice.infrastructure.redis;

import com.platform.promotionservice.config.PromotionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Pipeline individual GETs: snapshot keys have different Redis Cluster hash tags. */
    public Map<Long, String> getAll(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            List<Object> values = redis.executePipelined((RedisCallback<Object>) connection -> {
                for (long id : ids) {
                    connection.stringCommands().get(redis.getStringSerializer().serialize(PromotionRedisKeys.snapshot(id)));
                }
                return null;
            });
            Map<Long, String> result = new LinkedHashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                if (values.get(i) instanceof String json) {
                    result.put(ids.get(i), json);
                }
            }
            return result;
        } catch (RuntimeException e) {
            log.debug("Snapshot batch read failed: {}", e.getMessage());
            return Map.of();
        }
    }

    public void putAll(Map<Long, String> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        try {
            redis.executePipelined((RedisCallback<Object>) connection -> {
                snapshots.forEach((id, json) -> connection.stringCommands().set(
                        redis.getStringSerializer().serialize(PromotionRedisKeys.snapshot(id)),
                        redis.getStringSerializer().serialize(json),
                        Expiration.from(properties.getSnapshot().getTtl()), SetOption.UPSERT));
                return null;
            });
        } catch (RuntimeException e) {
            log.debug("Snapshot batch write failed: {}", e.getMessage());
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
