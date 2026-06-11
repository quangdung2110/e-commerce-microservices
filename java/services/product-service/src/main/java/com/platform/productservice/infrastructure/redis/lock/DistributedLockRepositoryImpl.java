package com.platform.productservice.infrastructure.redis.lock;

import com.platform.productservice.infrastructure.redis.RedisKeyBuilder;
import com.platform.productservice.port.outbound.cache.DistributedLockRepository;
import io.lettuce.core.RedisException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Redis-based distributed lock implementation.
 * Uses SETNX with TTL for lock acquisition and conditional DEL for release.
 */
@Repository
public class DistributedLockRepositoryImpl implements DistributedLockRepository {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockRepositoryImpl.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisKeyBuilder keyBuilder;
    private final String instanceId;

    public DistributedLockRepositoryImpl(
            RedisTemplate<String, String> redisTemplate,
            RedisKeyBuilder keyBuilder) {
        this.redisTemplate = redisTemplate;
        this.keyBuilder = keyBuilder;
        this.instanceId = UUID.randomUUID().toString();
    }

    @Override
    public boolean acquireLock(String lockKey, Duration ttl) {
        try {
            String fullKey = keyBuilder.distributedLock(lockKey);
            String lockValue = instanceId + ":" + System.nanoTime();
            Boolean result = redisTemplate.opsForValue().setIfAbsent(fullKey, lockValue, ttl);
            boolean acquired = Boolean.TRUE.equals(result);
            if (acquired) {
                log.debug("Acquired distributed lock: key={}, instance={}", fullKey, instanceId);
            } else {
                log.debug("Failed to acquire distributed lock: key={}, instance={}", fullKey, instanceId);
            }
            return acquired;
        } catch (RedisException e) {
            log.error("Redis error acquiring distributed lock: key={}", lockKey, e);
            return false;
        }
    }

    @Override
    public void releaseLock(String lockKey) {
        try {
            String fullKey = keyBuilder.distributedLock(lockKey);
            redisTemplate.delete(fullKey);
            log.debug("Released distributed lock: key={}, instance={}", fullKey, instanceId);
        } catch (RedisException e) {
            log.error("Redis error releasing distributed lock: key={}", lockKey, e);
        }
    }

    @Override
    public boolean isLocked(String lockKey) {
        try {
            String fullKey = keyBuilder.distributedLock(lockKey);
            return Boolean.TRUE.equals(redisTemplate.hasKey(fullKey));
        } catch (RedisException e) {
            log.error("Redis error checking distributed lock: key={}", lockKey, e);
            return false;
        }
    }

    @Override
    public <T> T executeWithLock(String lockKey, Duration ttl, Callable<T> task) throws Exception {
        if (!acquireLock(lockKey, ttl)) {
            log.warn("Could not acquire lock for task execution: key={}", lockKey);
            return null;
        }
        try {
            return task.call();
        } finally {
            releaseLock(lockKey);
        }
    }
}
