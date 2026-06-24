package com.platform.inventoryservice.infrastructure.redis.adapter;

import com.platform.inventoryservice.config.InventoryProperties;
import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;
import com.platform.inventoryservice.infrastructure.redis.key.RedisKeyBuilder;
import com.platform.inventoryservice.port.outbound.cache.SuspensionFlagManager;
import com.platform.inventoryservice.port.outbound.cache.SuspensionInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Component
public class SuspensionFlagManagerImpl implements SuspensionFlagManager {

    private final StringRedisTemplate redis;
    private final RedisKeyBuilder keys;
    private final long suspensionTtlSec;

    public SuspensionFlagManagerImpl(StringRedisTemplate redis, RedisKeyBuilder keys, InventoryProperties props) {
        this.redis = redis;
        this.keys = keys;
        this.suspensionTtlSec = props.getRedis().getSuspensionTtlHours() * 60L * 60;
    }

    @Override
    public void suspend(SkuWarehouseKey key, String reason) {
        String suspendKey = keys.suspensionKey(key);
        Map<String, String> hash = Map.of(
            "lock_reason", reason,
            "locked_at", String.valueOf(System.currentTimeMillis()),
            "status", "SUSPENDED"
        );
        redis.opsForHash().putAll(suspendKey, hash);
        redis.expire(suspendKey, Duration.ofSeconds(suspensionTtlSec));
    }

    @Override
    public boolean unsuspend(SkuWarehouseKey key) {
        return Boolean.TRUE.equals(redis.delete(keys.suspensionKey(key)));
    }

    @Override
    public boolean isSuspended(SkuWarehouseKey key) {
        return Boolean.TRUE.equals(redis.hasKey(keys.suspensionKey(key)));
    }

    @Override
    public Optional<SuspensionInfo> getInfo(SkuWarehouseKey key) {
        Map<Object, Object> entries = redis.opsForHash().entries(keys.suspensionKey(key));
        if (entries.isEmpty()) return Optional.empty();
        return Optional.of(new SuspensionInfo(
            String.valueOf(entries.get("lock_reason")),
            parseLong(entries.get("locked_at")),
            String.valueOf(entries.get("status"))
        ));
    }

    private long parseLong(Object v) {
        if (v == null) return 0;
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
