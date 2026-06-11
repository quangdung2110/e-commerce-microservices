package com.platform.productservice.infrastructure.redis.sync;

import com.platform.productservice.infrastructure.redis.RedisKeyBuilder;
import com.platform.productservice.infrastructure.redis.cache.model.PendingSyncItem;
import com.platform.productservice.port.outbound.cache.InventorySyncRepository;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.api.sync.RedisSetCommands;
import io.lettuce.core.api.sync.RedisSortedSetCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis adapter implementing InventorySyncRepository.
 * Manages the pending inventory sync ZSET queue.
 */
@Repository
public class InventorySyncRepositoryImpl implements InventorySyncRepository {

    private static final Logger log = LoggerFactory.getLogger(InventorySyncRepositoryImpl.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisKeyBuilder keyBuilder;
    private final String pendingKey;

    public InventorySyncRepositoryImpl(
            RedisTemplate<String, String> redisTemplate,
            RedisKeyBuilder keyBuilder) {
        this.redisTemplate = redisTemplate;
        this.keyBuilder = keyBuilder;
        this.pendingKey = keyBuilder.inventoryPending();
    }

    @Override
    public void addPendingSync(String skuId, long eventTimestamp, int quantity, String skuStatus) {
        try {
            String member = keyBuilder.pendingMember(skuId, eventTimestamp);
            // Store metadata as JSON in a separate hash for retrieval
            String metaKey = pendingKey + ":meta:" + member;
            PendingSyncItem item = PendingSyncItem.builder()
                    .skuId(skuId)
                    .eventTimestamp(eventTimestamp)
                    .availableQuantity(quantity)
                    .skuStatus(skuStatus)
                    .build();
            redisTemplate.opsForValue().set(metaKey, item.toJson());
            // ZADD to pending queue with score = eventTimestamp
            redisTemplate.opsForZSet().add(pendingKey, member, eventTimestamp);
            log.debug("Added pending sync: skuId={}, timestamp={}, quantity={}", skuId, eventTimestamp, quantity);
        } catch (RedisException e) {
            log.error("Redis error adding pending sync: skuId={}", skuId, e);
        }
    }

    @Override
    public List<PendingSyncItem> drainPendingSync(int limit) {
        try {
            List<PendingSyncItem> items = new ArrayList<>();
            Set<io.lettuce.core.ScoredValue<String>> results =
                    redisTemplate.opsForZSet().popMin(pendingKey, limit);
            if (results == null || results.isEmpty()) {
                return items;
            }
            for (io.lettuce.core.ScoredValue<String> sv : results) {
                String member = sv.getValue();
                PendingSyncItem item = PendingSyncItem.fromMemberString(member);
                if (item != null) {
                    // Restore additional fields from metadata hash
                    String metaKey = pendingKey + ":meta:" + member;
                    String metaJson = redisTemplate.opsForValue().get(metaKey);
                    if (metaJson != null && !metaJson.isBlank()) {
                        PendingSyncItem fullItem = PendingSyncItem.fromJson(metaJson);
                        if (fullItem != null) {
                            item = fullItem;
                        }
                    }
                    items.add(item);
                }
            }
            log.debug("Drained {} pending sync items", items.size());
            return items;
        } catch (RedisException e) {
            log.error("Redis error draining pending sync queue: limit={}", limit, e);
            return List.of();
        }
    }

    @Override
    public void acknowledgePendingSync(String member) {
        try {
            redisTemplate.opsForZSet().remove(pendingKey, member);
            String metaKey = pendingKey + ":meta:" + member;
            redisTemplate.delete(metaKey);
            log.debug("Acknowledged pending sync: member={}", member);
        } catch (RedisException e) {
            log.error("Redis error acknowledging pending sync: member={}", member, e);
        }
    }

    @Override
    public void acknowledgePendingSync(List<String> members) {
        if (members == null || members.isEmpty()) {
            return;
        }
        try {
            for (String member : members) {
                redisTemplate.opsForZSet().remove(pendingKey, member);
                String metaKey = pendingKey + ":meta:" + member;
                redisTemplate.delete(metaKey);
            }
            log.info("Batch acknowledged {} pending sync items", members.size());
        } catch (RedisException e) {
            log.error("Redis error batch acknowledging pending sync: count={}", members.size(), e);
        }
    }

    @Override
    public long getPendingSyncCount() {
        try {
            Long count = redisTemplate.opsForZSet().zCard(pendingKey);
            return count != null ? count : 0L;
        } catch (RedisException e) {
            log.error("Redis error getting pending sync count", e);
            return 0L;
        }
    }

    @Override
    public void requeuePendingSync(List<PendingSyncItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        try {
            for (PendingSyncItem item : items) {
                String member = item.getMemberString();
                String metaKey = pendingKey + ":meta:" + member;
                redisTemplate.opsForValue().set(metaKey, item.toJson());
                redisTemplate.opsForZSet().add(pendingKey, member, item.getEventTimestamp());
            }
            log.info("Requeued {} pending sync items", items.size());
        } catch (RedisException e) {
            log.error("Redis error requeuing pending sync: count={}", items.size(), e);
        }
    }
}
