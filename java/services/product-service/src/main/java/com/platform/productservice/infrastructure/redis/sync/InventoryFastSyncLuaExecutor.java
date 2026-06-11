package com.platform.productservice.infrastructure.redis.sync;

import com.platform.productservice.infrastructure.redis.RedisKeyBuilder;
import com.platform.productservice.infrastructure.redis.script.LuaScriptLoader;
import io.lettuce.core.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes the inventory_fast_sync.lua Lua script for Phase 1 fast-path inventory sync.
 * Implements CAS (compare-and-swap) logic with staleness guards.
 */
@Component
public class InventoryFastSyncLuaExecutor {

    private static final Logger log = LoggerFactory.getLogger(InventoryFastSyncLuaExecutor.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisKeyBuilder keyBuilder;
    private final String scriptContent;

    public InventoryFastSyncLuaExecutor(
            RedisTemplate<String, String> redisTemplate,
            RedisKeyBuilder keyBuilder) {
        this.redisTemplate = redisTemplate;
        this.keyBuilder = keyBuilder;
        this.scriptContent = loadScript("classpath:lua/inventory_fast_sync.lua");
    }

    private String loadScript(String classpath) {
        try {
            Resource resource = new PathMatchingResourcePatternResolver().getResource(classpath);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load Lua script: " + classpath, e);
        }
    }

    /**
     * Executes the inventory fast sync Lua script for a single update.
     *
     * @param skuId             the SKU ID
     * @param eventTimestamp    the event timestamp (epoch millis)
     * @param availableQuantity the available quantity
     * @param productId         the product ID (for validation)
     * @param skuStatus         the SKU status
     * @param skuJson           the full SKU JSON for cache overwrite
     * @return the sync result
     */
    public LuaSyncResult execute(
            String skuId,
            long eventTimestamp,
            int availableQuantity,
            String productId,
            String skuStatus,
            String skuJson) {

        try {
            String skuKey = keyBuilder.sku(skuId);
            String pendingKey = keyBuilder.inventoryPending();
            String member = keyBuilder.pendingMember(skuId, eventTimestamp);

            DefaultRedisScript<Long> script = new DefaultRedisScript<>(scriptContent, Long.class);

            Long result = redisTemplate.execute(
                    script,
                    List.of(skuKey, pendingKey),
                    skuId,
                    String.valueOf(availableQuantity),
                    String.valueOf(eventTimestamp),
                    productId,
                    skuStatus,
                    skuJson,
                    member
            );

            return LuaSyncResult.fromResult(result, skuId, eventTimestamp);
        } catch (RedisException e) {
            log.error("Redis error executing fast sync: skuId={}, timestamp={}", skuId, eventTimestamp, e);
            return LuaSyncResult.failure(skuId, eventTimestamp, e.getMessage());
        }
    }

    /**
     * Executes the inventory fast sync Lua script in batch using pipelining.
     *
     * @param updates list of inventory updates
     * @return list of sync results
     */
    public List<LuaSyncResult> executeBatch(List<InventoryUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return List.of();
        }
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(scriptContent, Long.class);
            List<Object> pipelineResults = redisTemplate.executePipelined(
                    (RedisCallback<Object>) connection -> {
                        for (InventoryUpdate update : updates) {
                            String skuKey = keyBuilder.sku(update.skuId);
                            String pendingKey = keyBuilder.inventoryPending();
                            String member = keyBuilder.pendingMember(update.skuId, update.eventTimestamp);

                            List<String> keys = List.of(skuKey, pendingKey);
                            List<String> args = List.of(
                                    update.skuId,
                                    String.valueOf(update.availableQuantity),
                                    String.valueOf(update.eventTimestamp),
                                    update.productId,
                                    update.skuStatus,
                                    update.skuJson,
                                    member
                            );

                            byte[][] keysArray = new byte[keys.size()][];
                            byte[][] argsArray = new byte[args.size()][];
                            for (int i = 0; i < keys.size(); i++) {
                                keysArray[i] = keys.get(i).getBytes(StandardCharsets.UTF_8);
                            }
                            for (int i = 0; i < args.size(); i++) {
                                argsArray[i] = args.get(i).getBytes(StandardCharsets.UTF_8);
                            }

                            byte[][] allArgs = new byte[keysArray.length + argsArray.length][];
                            System.arraycopy(keysArray, 0, allArgs, 0, keysArray.length);
                            System.arraycopy(argsArray, 0, allArgs, keysArray.length, argsArray.length);

                            connection.eval(
                                    scriptContent.getBytes(StandardCharsets.UTF_8),
                                    org.springframework.data.redis.connection.ReturnType.INTEGER,
                                    2,
                                    allArgs
                            );
                        }
                        return null;
                    }
            );
            List<LuaSyncResult> results = new ArrayList<>(updates.size());
            for (int i = 0; i < updates.size(); i++) {
                InventoryUpdate update = updates.get(i);
                Object result = pipelineResults.get(i);
                Long resultLong = result instanceof Long ? (Long) result : null;
                results.add(LuaSyncResult.fromResult(resultLong, update.skuId, update.eventTimestamp));
            }
            log.info("Batch fast sync executed: count={}", updates.size());
            return results;
        } catch (RedisException e) {
            log.error("Redis error executing batch fast sync: count={}", updates.size(), e);
            List<LuaSyncResult> results = new ArrayList<>(updates.size());
            for (InventoryUpdate update : updates) {
                results.add(LuaSyncResult.failure(update.skuId, update.eventTimestamp, e.getMessage()));
            }
            return results;
        }
    }

    /**
     * Represents a single inventory update for batch processing.
     */
    public static class InventoryUpdate {
        public final String skuId;
        public final long eventTimestamp;
        public final int availableQuantity;
        public final String productId;
        public final String skuStatus;
        public final String skuJson;

        public InventoryUpdate(String skuId, long eventTimestamp, int availableQuantity,
                              String productId, String skuStatus, String skuJson) {
            this.skuId = skuId;
            this.eventTimestamp = eventTimestamp;
            this.availableQuantity = availableQuantity;
            this.productId = productId;
            this.skuStatus = skuStatus;
            this.skuJson = skuJson;
        }
    }

    /**
     * Represents the result of a Lua script execution.
     */
    public static class LuaSyncResult {
        public final boolean success;
        public final boolean applied;
        public final boolean stale;
        public final boolean cacheMiss;
        public final String message;
        public final String skuId;
        public final long eventTimestamp;

        private LuaSyncResult(boolean success, boolean applied, boolean stale, boolean cacheMiss,
                              String message, String skuId, long eventTimestamp) {
            this.success = success;
            this.applied = applied;
            this.stale = stale;
            this.cacheMiss = cacheMiss;
            this.message = message;
            this.skuId = skuId;
            this.eventTimestamp = eventTimestamp;
        }

        public static LuaSyncResult fromResult(Long result, String skuId, long eventTimestamp) {
            if (result == null) {
                return failure(skuId, eventTimestamp, "null result");
            }
            switch (result.intValue()) {
                case 1:
                    return new LuaSyncResult(true, true, false, false,
                            "Applied successfully", skuId, eventTimestamp);
                case 0:
                    return new LuaSyncResult(true, false, true, false,
                            "Stale event rejected", skuId, eventTimestamp);
                case -1:
                    return new LuaSyncResult(true, false, false, true,
                            "Cache miss - ZADD only", skuId, eventTimestamp);
                default:
                    return failure(skuId, eventTimestamp, "Unknown result: " + result);
            }
        }

        public static LuaSyncResult failure(String skuId, long eventTimestamp, String reason) {
            return new LuaSyncResult(false, false, false, false, reason, skuId, eventTimestamp);
        }
    }
}
