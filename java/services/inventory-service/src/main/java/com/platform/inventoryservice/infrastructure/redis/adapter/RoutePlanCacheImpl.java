package com.platform.inventoryservice.infrastructure.redis.adapter;

import com.platform.inventoryservice.infrastructure.redis.key.RedisKeyBuilder;
import com.platform.inventoryservice.port.outbound.cache.RoutePlanCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Component
public class RoutePlanCacheImpl implements RoutePlanCache {

    private final StringRedisTemplate redis;
    private final RedisKeyBuilder keys;

    public RoutePlanCacheImpl(StringRedisTemplate redis, RedisKeyBuilder keys) {
        this.redis = redis;
        this.keys = keys;
    }

    @Override
    public void save(UUID routePlanId, String planJson, Duration ttl) {
        redis.opsForValue().set(keys.routePlanKey(routePlanId), planJson, ttl);
    }

    @Override
    public Optional<String> find(UUID routePlanId) {
        return Optional.ofNullable(redis.opsForValue().get(keys.routePlanKey(routePlanId)));
    }

    @Override
    public boolean exists(UUID routePlanId) {
        return Boolean.TRUE.equals(redis.hasKey(keys.routePlanKey(routePlanId)));
    }
}
