package com.ecommerce.shipping.infrastructure.application;

import com.ecommerce.shipping.application.port.out.IdempotencyPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Theo thiết kế shipping_workflow.md mục 2 và shipping_events.md mục 3.6: idempotency theo SETNX + TTL.
@Component
public class RedisIdempotencyAdapter implements IdempotencyPort {

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdempotencyAdapter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean acquire(String key, Duration ttl) {
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
        return Boolean.TRUE.equals(acquired);
    }
}
