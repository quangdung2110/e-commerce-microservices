package com.platform.promotionservice.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/** {@code promo:{id}:reconcile_lock}: serialize repair theo promotion (SET NX PX). */
@Component
@RequiredArgsConstructor
public class ReconcileLock {

    private final StringRedisTemplate redis;

    public String tryLock(long promotionId, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(PromotionRedisKeys.reconcileLock(promotionId), token, ttl);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    public void unlock(long promotionId, String token) {
        String key = PromotionRedisKeys.reconcileLock(promotionId);
        String current = redis.opsForValue().get(key);
        if (token != null && token.equals(current)) {
            redis.delete(key);
        }
    }
}
